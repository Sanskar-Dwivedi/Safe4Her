package `in`.security4her.app.sos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import `in`.security4her.app.BuildConfig
import `in`.security4her.app.MainActivity
import `in`.security4her.app.R
import `in`.security4her.app.core.Backend
import `in`.security4her.app.core.DeviceLink
import `in`.security4her.app.core.IncidentRef
import `in`.security4her.app.core.Paths
import `in`.security4her.app.core.PhoneStatus
import `in`.security4her.app.core.S4hJson
import `in`.security4her.app.core.Settings
import `in`.security4her.app.core.SosLocationMsg
import `in`.security4her.app.core.SosStartMsg
import `in`.security4her.app.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "S4hSos"

@Serializable
data class Recipient(val name: String, val phone: String, val police: Boolean = false, val state: String = "sending", val error: String? = null)

@Serializable
data class SosEvent(val at: Long, val text: String, val kind: String = "info") // info | ok | fail

/** One SOS, persisted on every change so the Active SOS screen survives a process restart. */
@Serializable
data class Incident(
    val id: String,
    val trigger: String,
    val source: String,
    val startedAt: Long,
    val test: Boolean,
    val state: String = "active", // active | resolved | cancelled
    val lat: Double? = null,
    val lng: Double? = null,
    val acc: Float? = null,
    val fixAt: Long? = null,
    val server: String = "pending", // pending | ok | retrying | failed
    val shareUrl: String? = null,
    val recipients: List<Recipient> = emptyList(),
    val callNumber: String? = null,
    val callAt: Long? = null,
    val call: String = "waiting", // waiting | placed | cancelled | skipped | failed
    val smsNeedsLocation: Boolean = false, // first text went out before GPS had a fix
    val whatsappOpened: Boolean = false,
    val events: List<SosEvent> = emptyList(),
) {
    val active get() = state == "active"
    val smsSent get() = recipients.count { it.state == "sent" || it.state == "delivered" }
    val smsFailed get() = recipients.count { it.state == "failed" }
}

data class Fix(val lat: Double, val lng: Double, val acc: Float?, val at: Long)

/** The single SOS pipeline used by the Home button and by the watch (CODEX_BRIEF §3). */
object SosEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _current = MutableStateFlow<Incident?>(null)
    val current: StateFlow<Incident?> = _current.asStateFlow()
    private val jobs = mutableListOf<Job>()
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        _current.value = Store.prefs(app).getString("incident", null)
            ?.let { runCatching { S4hJson.decodeFromString(Incident.serializer(), it) }.getOrNull() }
    }

    @Synchronized
    fun start(ctx: Context, id: String = UUID.randomUUID().toString(), trigger: String, source: String, fix: Fix? = null) {
        val live = _current.value
        if (live != null && live.active) return // one SOS at a time; the watch and phone dedupe on id
        val s = Settings.load(ctx)
        val how = when (trigger) { "knock" -> "Knock Knock on watch"; "shake" -> "shake on watch"; else -> "SOS button" }
        _current.value = Incident(
            id = id, trigger = trigger, source = source, startedAt = System.currentTimeMillis(), test = s.testMode,
            lat = fix?.lat, lng = fix?.lng, acc = fix?.acc, fixAt = fix?.at,
            events = listOf(SosEvent(System.currentTimeMillis(), "SOS started ($how)")),
        )
        persist()
        runCatching { ContextCompat.startForegroundService(app, Intent(app, SosService::class.java)) }
            .onFailure { log("Background service blocked; running in app only", "fail") }
        jobs += scope.launch { pipeline(s) }
    }

    private suspend fun pipeline(s: Settings) {
        val inc = _current.value ?: return
        // 1. Location: last known now, a fresh GPS fix as soon as it lands
        if (inc.lat == null) lastFix()?.let { setFix(it, "Last known location") }
        jobs += scope.launch {
            withTimeoutOrNull(20_000) { freshFix() }?.let { setFix(it, "Location fixed"); followUpLocation(s) }
        }
        // 2. Server (share link)
        jobs += scope.launch { serverUpsert() }
        // Give GPS up to 8 s so the first text carries a location; a follow-up text goes out otherwise.
        withTimeoutOrNull(8_000) { while (!hasFreshFix()) delay(200) }
        if (!Backend.mock) withTimeoutOrNull(2_000) { while (_current.value?.shareUrl == null && _current.value?.server != "failed") delay(200) }
        // 3. SMS
        sendSos(s)
        // 4. Countdown call
        jobs += scope.launch { countdownCall(s) }
        // 5. Location updates every 30 s
        jobs += scope.launch {
            while (true) {
                delay(30_000)
                if (_current.value?.active != true) break
                freshFix()?.let { setFix(it, null); pushLocation(it) }
            }
        }
    }

    private suspend fun serverUpsert() {
        repeat(5) { attempt ->
            val inc = _current.value ?: return
            if (!inc.active) return
            try {
                val token = Backend.upsertIncident(inc.id, inc.trigger, inc.source, inc.lat, inc.lng, inc.acc, inc.test)
                val url = BuildConfig.TRACK_BASE_URL.ifBlank { "https://sanskar-dwivedi.github.io/Safe4Her/" } + "?k=" + token
                update { it.copy(server = "ok", shareUrl = url) }
                log(if (Backend.mock) "Demo mode: no live link, texts carry a Google Maps link" else "Server accepted · live link ready", if (Backend.mock) "info" else "ok")
                return
            } catch (e: Exception) {
                Log.w(TAG, "upsert_incident failed", e)
                update { it.copy(server = "retrying") }
                if (attempt == 0) log("Server not reachable, retrying", "fail")
                delay(5_000L * (attempt + 1))
            }
        }
        update { it.copy(server = "failed") }
        log("Server unreachable. SMS carries the map link only", "fail")
    }

    private fun sendSos(s: Settings) {
        val inc = _current.value ?: return
        val contacts = runCatching { runBlocking { Backend.contacts() } }.getOrDefault(emptyList())
        val list = contacts.filter { it.receivesSos }.map { Recipient(it.name, it.phone) }.toMutableList()
        val police = if (s.testMode) s.demoNumber else s.policeNumber
        if (police.isNotBlank()) list += Recipient(if (s.testMode) "Demo number (Test Mode)" else "Police $police", police, police = true)
        else if (s.testMode) log("Test Mode: no demo number set, police SMS skipped", "fail")
        if (list.isEmpty()) {
            log("No one to text. Add trusted contacts.", "fail")
            return
        }
        update { it.copy(recipients = list, smsNeedsLocation = it.lat == null) }
        val body = smsBody(s)
        log("Texting ${list.size} ${if (list.size == 1) "person" else "people"}")
        list.forEach { r -> sendSms(r.phone, body, inc.id) }
    }

    private fun userName() = runCatching { runBlocking { Backend.profile().displayName } }.getOrNull() ?: "a Security4Her user"

    private fun mapsLink(lat: Double, lng: Double) = "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, lat, lng)

    /** The SOS message, shared by SMS and WhatsApp. */
    fun sosText(): String = smsBody(Settings.load(app))

    private fun smsBody(s: Settings): String {
        val inc = _current.value!!
        val name = userName()
        return buildString {
            append("SOS from $name. I may be in danger.")
            if (!Backend.mock) inc.shareUrl?.let { append(" Live location: $it") }
            if (inc.lat != null && inc.lng != null) {
                append(" My location: ${mapsLink(inc.lat, inc.lng)}")
                val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(inc.fixAt ?: System.currentTimeMillis()))
                append(" (±${inc.acc?.toInt() ?: "?"}m, $time)")
            } else append(" Location not available yet.")
            if (s.testMode) append(" [TEST]")
        }
    }

    private fun sendSms(phone: String, body: String, incidentId: String) {
        val err = Sms.send(app, phone, body, incidentId)
        if (err != null) {
            setRecipient(phone, "failed", err)
            log("Text to $phone failed: $err", "fail")
        }
    }

    fun onSmsResult(id: String, phone: String, action: String, ok: Boolean, code: Int) {
        if (_current.value?.id != id) return
        val r = _current.value?.recipients?.firstOrNull { it.phone == phone } ?: return
        val next = when {
            action == SosReceiver.DELIVERED && ok -> "delivered"
            r.state == "failed" || r.state == "delivered" -> return
            ok -> "sent"
            else -> "failed"
        }
        if (next == r.state) return
        val err = if (next == "failed") Sms.reason(code) else null
        setRecipient(phone, next, err)
        log(
            "${r.name}: " + when (next) { "delivered" -> "text delivered"; "sent" -> "text sent"; else -> "text failed ($err)" },
            if (next == "failed") "fail" else "ok",
        )
    }

    private fun hasFreshFix(): Boolean {
        val at = _current.value?.fixAt ?: return false
        return System.currentTimeMillis() - at < 2 * 60_000
    }

    /** If the first text went out without a location, text the location as soon as GPS has it. */
    private fun followUpLocation(s: Settings) {
        val inc = _current.value ?: return
        if (!inc.active || !inc.smsNeedsLocation || inc.lat == null || inc.lng == null) return
        update { it.copy(smsNeedsLocation = false) }
        val body = "Location of ${userName()}: ${mapsLink(inc.lat, inc.lng)} (±${inc.acc?.toInt() ?: "?"}m)" + if (s.testMode) " [TEST]" else ""
        val to = inc.recipients.filter { it.state != "failed" }
        to.forEach { sendSms(it.phone, body, inc.id) }
        if (to.isNotEmpty()) log("Location texted to ${to.size}", "ok")
    }

    /** "Text my location again": fresh fix, then a text to everyone who got the SOS. */
    fun resendLocation() {
        scope.launch {
            val f = withTimeoutOrNull(12_000) { freshFix() } ?: lastFix()
            if (f == null) {
                log("Couldn't get a location fix. Is location on?", "fail")
                return@launch
            }
            setFix(f, null)
            pushLocation(f)
            val inc = _current.value ?: return@launch
            val s = Settings.load(app)
            val body = "Update from ${userName()}: ${mapsLink(f.lat, f.lng)} (±${f.acc?.toInt() ?: "?"}m)" + if (s.testMode) " [TEST]" else ""
            inc.recipients.forEach { sendSms(it.phone, body, inc.id) }
            log("Location update texted to ${inc.recipients.size}", "ok")
        }
    }

    fun markWhatsappOpened() = update { it.copy(whatsappOpened = true) }

    fun logWhatsapp(name: String) = log("WhatsApp opened for $name. Tap send there.")

    private suspend fun countdownCall(s: Settings) {
        val number = if (s.testMode) s.demoNumber else s.autoCallNumber.ifBlank {
            runCatching { Backend.contacts().firstOrNull { it.receivesSos }?.phone }.getOrNull().orEmpty()
        }
        if (number.isBlank()) {
            update { it.copy(call = "skipped") }
            log(if (s.testMode) "Auto-call skipped: set a demo number in Settings" else "Auto-call skipped: no number set", "fail")
            return
        }
        val at = System.currentTimeMillis() + s.countdownSec * 1000L
        update { it.copy(callNumber = number, callAt = at) }
        SosService.refresh(app)
        while (System.currentTimeMillis() < at) {
            delay(250)
            if (_current.value?.call != "waiting" || _current.value?.active != true) return
        }
        placeCall(number)
    }

    fun cancelCall() {
        if (_current.value?.call != "waiting") return
        update { it.copy(call = "cancelled") }
        log("Auto-call cancelled")
        SosService.refresh(app)
    }

    @SuppressLint("MissingPermission")
    private fun placeCall(number: String) {
        val uri = Uri.fromParts("tel", number, null)
        val ok = try {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) error("no CALL_PHONE")
            app.getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
            true
        } catch (e: Exception) {
            Log.w(TAG, "placeCall failed", e)
            false
        }
        update { it.copy(call = if (ok) "placed" else "failed") }
        log(if (ok) "Call placed to $number" else "Couldn't place the call. Tap Call 112.", if (ok) "ok" else "fail")
        SosService.refresh(app)
    }

    /** From the watch (/s4h/sos/location) or our own 30 s loop. */
    fun onWatchLocation(msg: SosLocationMsg) {
        if (_current.value?.id != msg.incidentId) return
        val f = Fix(msg.lat, msg.lng, msg.accuracyM, msg.fixAt)
        setFix(f, null)
        scope.launch { pushLocation(f) }
    }

    fun end(status: String) {
        val inc = _current.value ?: return
        if (!inc.active) return
        jobs.forEach { it.cancel() }
        jobs.clear()
        update { it.copy(state = status, call = if (it.call == "waiting") "cancelled" else it.call) }
        log(if (status == "resolved") "Marked safe" else "SOS cancelled", "ok")
        scope.launch {
            runCatching { Backend.endIncident(inc.id, status) }.onFailure { Log.w(TAG, "end_incident failed", it) }
            val told = inc.recipients.filter { it.state == "sent" || it.state == "delivered" }
            if (told.isNotEmpty()) {
                val name = runCatching { Backend.profile().displayName }.getOrNull() ?: "She"
                told.forEach { sendSms(it.phone, "$name is safe now. Thank you. (Security4Her)", inc.id) }
                log("\"I'm safe\" text sent to ${told.size}", "ok")
            }
        }
        app.stopService(Intent(app, SosService::class.java))
    }

    fun dismiss() {
        if (_current.value?.active == true) return
        _current.value = null
        Store.prefs(app).edit().remove("incident").apply()
    }

    // ---- helpers ----

    private fun setFix(f: Fix, label: String?) {
        update { it.copy(lat = f.lat, lng = f.lng, acc = f.acc, fixAt = f.at) }
        if (label != null) log("$label (±${f.acc?.toInt() ?: "?"} m)", "ok")
    }

    private suspend fun pushLocation(f: Fix) {
        val inc = _current.value ?: return
        if (!inc.active || inc.server != "ok") return
        runCatching { Backend.addLocation(inc.id, f.lat, f.lng, f.acc, Instant.ofEpochMilli(f.at).toString()) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastFix(): Fix? = runCatching {
        if (!hasLocation()) return null
        LocationServices.getFusedLocationProviderClient(app).lastLocation.await()
            ?.let { Fix(it.latitude, it.longitude, it.accuracy, it.time) }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private suspend fun freshFix(): Fix? = runCatching {
        if (!hasLocation()) return null
        LocationServices.getFusedLocationProviderClient(app).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            ?.let { Fix(it.latitude, it.longitude, it.accuracy, System.currentTimeMillis()) }
    }.getOrNull()

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun setRecipient(phone: String, state: String, error: String? = null) =
        update { inc -> inc.copy(recipients = inc.recipients.map { if (it.phone == phone) it.copy(state = state, error = error) else it }) }

    private fun log(text: String, kind: String = "info") =
        update { it.copy(events = it.events + SosEvent(System.currentTimeMillis(), text, kind)) }

    @Synchronized
    private fun update(f: (Incident) -> Incident) {
        val next = _current.value?.let(f) ?: return
        _current.value = next
        persist()
        scope.launch { WearBridge.putStatus(app, next) }
    }

    private fun persist() {
        val inc = _current.value ?: return
        Store.prefs(app).edit().putString("incident", S4hJson.encodeToString(Incident.serializer(), inc)).apply()
    }
}

/** Phone ↔ watch over the Wear Data Layer (CONTRACT.md §2). Every call tolerates a missing watch. */
object WearBridge {
    suspend fun putStatus(ctx: Context, inc: Incident) = runCatching {
        val status = PhoneStatus(
            incidentId = inc.id, state = inc.state, smsSent = inc.smsSent, smsFailed = inc.smsFailed,
            serverAccepted = inc.server == "ok", callPlaced = inc.call == "placed", shareUrl = inc.shareUrl,
            updatedAt = System.currentTimeMillis(),
        )
        put(ctx, Paths.STATUS, S4hJson.encodeToString(PhoneStatus.serializer(), status))
    }

    /** register_watch() → /s4h/device. Call after login/verify, on Test Mode change, and on /s4h/link/request. */
    suspend fun linkWatch(ctx: Context) = runCatching {
        val p = Backend.profile()
        if (!p.verifiedFemale) return@runCatching
        val token = Backend.registerWatch()
        val link = DeviceLink(p.id, p.displayName.orEmpty(), token, Settings.load(ctx).testMode)
        put(ctx, Paths.DEVICE, S4hJson.encodeToString(DeviceLink.serializer(), link))
    }.onFailure { Log.w(TAG, "linkWatch failed", it) }

    suspend fun watchReachable(ctx: Context): Boolean = runCatching {
        Wearable.getCapabilityClient(ctx).getCapability(Paths.CAP_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes.isNotEmpty()
    }.getOrDefault(false)

    private suspend fun put(ctx: Context, path: String, json: String) {
        val req = PutDataMapRequest.create(path).apply {
            dataMap.putString(Paths.DATA_KEY_JSON, json)
            dataMap.putLong("t", System.currentTimeMillis()) // forces onDataChanged even if json repeats
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }
}

/** Watch → phone messages. The ack goes out before any SMS or network work. */
class PhoneWearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val json = String(event.data, Charsets.UTF_8)
        runCatching {
            when (event.path) {
                Paths.SOS_START -> {
                    val msg = S4hJson.decodeFromString(SosStartMsg.serializer(), json)
                    runBlocking {
                        runCatching {
                            Wearable.getMessageClient(this@PhoneWearListenerService).sendMessage(
                                event.sourceNodeId, Paths.SOS_ACK,
                                S4hJson.encodeToString(IncidentRef.serializer(), IncidentRef(msg.incidentId)).toByteArray(),
                            ).await()
                        }
                    }
                    val fix = if (msg.lat != null && msg.lng != null) Fix(msg.lat, msg.lng, msg.accuracyM, msg.fixAt ?: msg.createdAt) else null
                    SosEngine.start(this, msg.incidentId, msg.trigger, "watch", fix)
                }
                Paths.SOS_LOCATION -> SosEngine.onWatchLocation(S4hJson.decodeFromString(SosLocationMsg.serializer(), json))
                Paths.SOS_CANCEL -> {
                    val ref = S4hJson.decodeFromString(IncidentRef.serializer(), json)
                    if (SosEngine.current.value?.id == ref.incidentId) SosEngine.end("resolved")
                }
                Paths.LINK_REQUEST -> runBlocking { WearBridge.linkWatch(this@PhoneWearListenerService) }
            }
        }.onFailure { Log.w(TAG, "bad message on ${event.path}: $json", it) }
    }
}

/** SMS sent/delivered results and notification actions. */
class SosReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SENT, DELIVERED -> {
                val id = intent.getStringExtra("id").orEmpty()
                val ok = resultCode == Activity.RESULT_OK
                if (id == Sms.TEST_ID) Sms.onTestResult(intent.action!!, ok, resultCode)
                else SosEngine.onSmsResult(id, intent.getStringExtra("phone").orEmpty(), intent.action!!, ok, resultCode)
            }
            CANCEL_CALL -> SosEngine.cancelCall()
        }
    }

    companion object {
        const val SENT = "in.security4her.app.SMS_SENT"
        const val DELIVERED = "in.security4her.app.SMS_DELIVERED"
        const val CANCEL_CALL = "in.security4her.app.CANCEL_CALL"
    }
}

/** Keeps the process alive during an SOS and shows the ongoing notification (type: location). */
class SosService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e) // e.g. no location permission on API 34+
            runCatching { getSystemService(NotificationManager::class.java).notify(ID, n) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 112
        private const val CHANNEL = "sos"

        fun refresh(ctx: Context) {
            if (SosEngine.current.value?.active != true) return
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx)) }
        }

        private fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "SOS", NotificationManager.IMPORTANCE_HIGH))
            }
            val inc = SosEngine.current.value
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
            )
            val dial112 = PendingIntent.getActivity(
                ctx, 1, Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            )
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("SOS active — tap to open")
                .setContentText(
                    when (inc?.call) {
                        "waiting" -> inc.callAt?.let { "Calling ${inc.callNumber} soon" } ?: "Sending your location"
                        "placed" -> "Call placed · ${inc.smsSent} texts sent"
                        else -> "${inc?.smsSent ?: 0} texts sent"
                    },
                )
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open)
                .addAction(0, "Call 112", dial112)
            if (inc?.call == "waiting" && inc.callAt != null) {
                val cancel = PendingIntent.getBroadcast(
                    ctx, 2, Intent(ctx, SosReceiver::class.java).setAction(SosReceiver.CANCEL_CALL), PendingIntent.FLAG_IMMUTABLE,
                )
                b.addAction(0, "Cancel call", cancel).setWhen(inc.callAt).setUsesChronometer(true)
                if (Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true)
            }
            return b.build()
        }
    }
}

/** SMS sending that picks the right SIM on dual-SIM phones and explains failures in plain words. */
object Sms {
    const val TEST_ID = "test"
    private val _test = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _test.asStateFlow()

    fun clean(phone: String) = phone.filter { it.isDigit() || it == '+' }

    @SuppressLint("MissingPermission")
    private fun manager(ctx: Context): SmsManager {
        var sub = SmsManager.getDefaultSmsSubscriptionId()
        if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            sub = runCatching {
                ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
            }.getOrNull() ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        return if (Build.VERSION.SDK_INT >= 31) {
            val m = ctx.getSystemService(SmsManager::class.java)
            if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) m.createForSubscriptionId(sub) else m
        } else {
            @Suppress("DEPRECATION")
            if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) SmsManager.getSmsManagerForSubscriptionId(sub) else SmsManager.getDefault()
        }
    }

    /** Queues the text; returns null when handed to the radio, else a reason it couldn't be. */
    fun send(ctx: Context, phone: String, body: String, id: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "SMS permission is off"
        }
        val to = clean(phone)
        if (to.count { it.isDigit() } < 3) return "number looks wrong"
        return try {
            val sms = manager(ctx)
            val parts = sms.divideMessage(body)
            fun pi(action: String, i: Int) = PendingIntent.getBroadcast(
                ctx, (to + action + id + i).hashCode(),
                Intent(ctx, SosReceiver::class.java).setAction(action).putExtra("phone", phone).putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val sent = ArrayList(parts.indices.map { pi(SosReceiver.SENT, it) })
            val delivered = ArrayList(parts.indices.map { pi(SosReceiver.DELIVERED, it) })
            sms.sendMultipartTextMessage(to, null, parts, sent, delivered)
            null
        } catch (e: Exception) {
            Log.w(TAG, "SMS to $to failed", e)
            e.message?.take(60) ?: "phone refused to send"
        }
    }

    fun reason(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "carrier or SIM refused it; check SMS balance"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile signal"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "airplane mode or SIM off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "message couldn't be built"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Android paused sending, too many texts"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "short numbers are blocked"
        else -> "error $code"
    }

    fun sendTest(ctx: Context, phone: String) {
        _test.value = "Sending a test text to $phone"
        send(ctx, phone, "Security4Her test: if you got this, SOS texts from this phone work.", TEST_ID)?.let { _test.value = "Failed: $it" }
    }

    fun onTestResult(action: String, ok: Boolean, code: Int) {
        _test.value = when {
            action == SosReceiver.DELIVERED && ok -> "Delivered. SOS texts work from this phone."
            action == SosReceiver.DELIVERED -> _test.value
            ok -> "Sent. Waiting for the delivery report."
            else -> "Failed: ${reason(code)}"
        }
    }
}

/**
 * WhatsApp can't be sent silently by another app, so we open it with the SOS message typed in:
 * straight to one contact's chat, or to WhatsApp's chat picker (a family group, or several chats).
 */
object WhatsApp {
    private val packages = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun pkg(ctx: Context): String? = packages.firstOrNull {
        runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess
    }

    /** Digits with country code, as WhatsApp wants them. 10-digit numbers are treated as Indian (+91). */
    fun intl(phone: String): String {
        val d = phone.filter(Char::isDigit)
        return when {
            phone.trim().startsWith("+") -> d
            d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
            d.length == 10 -> "91$d"
            else -> d
        }
    }

    fun openChat(ctx: Context, phone: String, text: String): Boolean {
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${intl(phone)}&text=${Uri.encode(text)}")
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        pkg(ctx)?.let { i.setPackage(it) }
        return runCatching { ctx.startActivity(i) }.isSuccess
    }

    fun share(ctx: Context, text: String): Boolean {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val p = pkg(ctx)
        return runCatching {
            if (p != null) ctx.startActivity(i.setPackage(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else ctx.startActivity(Intent.createChooser(i, "Share location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}

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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.flow.first
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
import `in`.security4her.app.core.AppLog
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URL
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
    val liveTopic: String? = null, // ntfy topic carrying live location updates
    val liveUrl: String? = null,
    val livePushes: Int = 0,
    val liveFailing: Boolean = false,
    val reason: String? = null, // set when the SOS was raised automatically (Safe Journey)
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
    fun start(ctx: Context, id: String = UUID.randomUUID().toString(), trigger: String, source: String, fix: Fix? = null, reason: String? = null) {
        val live = _current.value
        if (live != null && live.active) return // one SOS at a time; the watch and phone dedupe on id
        val s = Settings.load(ctx)
        val how = when (trigger) {
            "knock" -> "Knock Knock on watch"; "shake" -> "shake on watch"; "journey" -> "Safe Journey: no answer to a check-in"; else -> "SOS button"
        }
        _current.value = Incident(
            id = id, trigger = trigger, source = source, startedAt = System.currentTimeMillis(), test = s.testMode,
            lat = fix?.lat, lng = fix?.lng, acc = fix?.acc, fixAt = fix?.at,
            events = listOfNotNull(SosEvent(System.currentTimeMillis(), "SOS started ($how)"), reason?.let { SosEvent(System.currentTimeMillis(), it, "fail") }),
            reason = reason,
        ).let { val t = Live.newTopic(); it.copy(liveTopic = t, liveUrl = Live.url(t)) }
        persist()
        Locator.start(app, "sos")
        if (!Locator.enabled(app)) log("Location is switched off on this phone. Turn it on so your contacts can see where you are.", "fail")
        AppLog.info("SOS", "SOS started ($how), Test Mode ${if (s.testMode) "on" else "off"}")
        runCatching { ContextCompat.startForegroundService(app, Intent(app, SosService::class.java)) }
            .onFailure { log("Background service blocked; running in app only", "fail") }
        jobs += scope.launch { pipeline(s) }
    }

    private suspend fun pipeline(s: Settings) {
        val inc = _current.value ?: return
        // 1. Location: last known now, a fresh GPS fix as soon as it lands
        if (inc.lat == null) lastFix()?.let { setFix(it, "Last known location") }
        jobs += scope.launch {
            withTimeoutOrNull(20_000) { freshFix() }?.let { setFix(it, "Location fixed"); followUpLocation(s); publishLive() }
        }
        // Live location: first post right away, then every 20 s until she's safe
        jobs += scope.launch {
            publishLive(first = true)
            var lastText = System.currentTimeMillis()
            while (true) {
                delay(20_000)
                if (_current.value?.active != true) break
                Locator.fresh(app, waitMs = 8_000)?.let { setFix(it, null); pushLocation(it) }
                publishLive()
                if (Settings.load(app).smsUpdates && System.currentTimeMillis() - lastText > 5 * 60_000) {
                    lastText = System.currentTimeMillis()
                    textUpdate()
                }
            }
        }
        // 2. Server (share link)
        jobs += scope.launch { serverUpsert() }
        // Give GPS up to 8 s so the first text carries a location; a follow-up text goes out otherwise.
        withTimeoutOrNull(8_000) { while (!hasFreshFix()) delay(200) }
        if (!Backend.mock) withTimeoutOrNull(2_000) { while (_current.value?.shareUrl == null && _current.value?.server != "failed") delay(200) }
        // Anonymous, rounded location for the community SOS-hotspot map.
        _current.value?.let { inc -> if (inc.lat != null && inc.lng != null) `in`.security4her.app.core.Community.recordSos(inc.lat, inc.lng, inc.test) }
        // 3. SMS
        sendSos(s)
        // 4. Countdown call
        jobs += scope.launch { countdownCall(s) }
    }

    private suspend fun publishLive(first: Boolean = false) {
        val inc = _current.value ?: return
        val topic = inc.liveTopic ?: return
        val name = userName()
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(inc.fixAt ?: System.currentTimeMillis()))
        val msg = if (inc.lat != null && inc.lng != null) {
            "%.6f, %.6f, accurate to %s m, at %s".format(Locale.US, inc.lat, inc.lng, inc.acc?.toInt() ?: "?", time)
        } else "Location not found yet. Updates follow."
        val ok = Live.publish(
            topic, if (first) "SOS: $name may be in danger" else "$name's live location", msg,
            inc.lat?.let { mapsLink(it, inc.lng!!) }, if (first) listOf("rotating_light") else listOf("round_pushpin"), if (first) 5 else 3,
        )
        val was = _current.value ?: return
        if (ok) {
            update { it.copy(livePushes = it.livePushes + 1, liveFailing = false) }
            if (was.livePushes == 0) log("Live location is on", "ok")
        } else if (!was.liveFailing) {
            update { it.copy(liveFailing = true) }
            log("Live location can't reach the internet, retrying", "fail")
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
                if (!Backend.mock) log("Server accepted the SOS", "ok")
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

    /** The SOS message. */
    fun sosText(): String = smsBody(Settings.load(app))

    private fun smsBody(s: Settings): String {
        val inc = _current.value!!
        val name = userName()
        return buildString {
            if (inc.reason != null) append("Automatic SOS for $name from Security4Her. ${inc.reason}")
            else append("SOS from $name. I may be in danger.")
            inc.liveUrl?.let { append(" Live location: $it") }
            if (inc.lat != null && inc.lng != null) {
                append(" My location: ${mapsLink(inc.lat, inc.lng)}")
                val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(inc.fixAt ?: System.currentTimeMillis()))
                append(" (accuracy ${inc.acc?.toInt() ?: "?"}m, $time)")
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

    /** Every 5 minutes during an SOS: the newest location by text, so contacts without internet can follow too. */
    private fun textUpdate() {
        val inc = _current.value ?: return
        if (!inc.active || inc.lat == null || inc.lng == null) return
        val to = inc.recipients.filter { it.state != "failed" }
        if (to.isEmpty()) return
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(inc.fixAt ?: System.currentTimeMillis()))
        val body = "Update from ${userName()}: ${mapsLink(inc.lat, inc.lng)} (accuracy ${inc.acc?.toInt() ?: "?"}m, $time)" +
            (inc.liveUrl?.let { " Live: $it" } ?: "") + if (inc.test) " [TEST]" else ""
        to.forEach { sendSms(it.phone, body, inc.id) }
        log("Location update texted to ${to.size}", "ok")
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
        val body = "Location of ${userName()}: ${mapsLink(inc.lat, inc.lng)} (accuracy ${inc.acc?.toInt() ?: "?"}m)" + if (s.testMode) " [TEST]" else ""
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
            val body = "Update from ${userName()}: ${mapsLink(f.lat, f.lng)} (accuracy ${f.acc?.toInt() ?: "?"}m)" + if (s.testMode) " [TEST]" else ""
            inc.recipients.forEach { sendSms(it.phone, body, inc.id) }
            log("Location update texted to ${inc.recipients.size}", "ok")
        }
    }



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
            inc.liveTopic?.let { Live.publish(it, "${userName()} is safe now", "Live location sharing has stopped.", null, listOf("white_check_mark"), 4) }
            runCatching { Backend.endIncident(inc.id, status) }.onFailure { AppLog.warn("SOS", "Server didn't record the end of the SOS", it) }
            val told = inc.recipients.filter { it.state == "sent" || it.state == "delivered" }
            if (told.isNotEmpty()) {
                val name = runCatching { Backend.profile().displayName }.getOrNull() ?: "She"
                told.forEach { sendSms(it.phone, "$name is safe now. Thank you. (Security4Her)", inc.id) }
                log("\"I'm safe\" text sent to ${told.size}", "ok")
            }
        }
        app.stopService(Intent(app, SosService::class.java))
        Locator.stop(app, "sos")
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

    private suspend fun lastFix(): Fix? = Locator.last(app)

    private suspend fun freshFix(): Fix? = Locator.fresh(app)

    private fun setRecipient(phone: String, state: String, error: String? = null) =
        update { inc -> inc.copy(recipients = inc.recipients.map { if (it.phone == phone) it.copy(state = state, error = error) else it }) }

    private fun log(text: String, kind: String = "info") {
        when (kind) { "ok" -> AppLog.ok("SOS", text); "fail" -> AppLog.warn("SOS", text); else -> AppLog.info("SOS", text) }
        update { it.copy(events = it.events + SosEvent(System.currentTimeMillis(), text, kind)) }
    }

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
    }.onSuccess { AppLog.ok("Watch", "Account details sent to the watch") }
        .onFailure {
            if ("API_UNAVAILABLE" in it.message.orEmpty()) AppLog.info("Watch", "No Wear OS companion on this phone, so no watch to link")
            else AppLog.warn("Watch", "Couldn't link the watch", it)
        }

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
    /** Watch audio evidence: /s4h/evidence/{incident}/{seq}/{startedAt}/{endedAt}/{sha256}. */
    override fun onChannelOpened(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
        val parts = channel.path.removePrefix("/s4h/evidence/").split("/")
        if (!channel.path.startsWith("/s4h/evidence/") || parts.size < 5) return
        val (incident, seq, start, end, sha) = parts
        runBlocking {
            runCatching {
                val client = Wearable.getChannelClient(this@PhoneWearListenerService)
                val file = Evidence.watchFile(this@PhoneWearListenerService, incident, seq.toInt())
                client.getInputStream(channel).await().use { input -> file.outputStream().use { input.copyTo(it) } }
                runCatching { client.close(channel).await() }
                Evidence.addWatchPiece(this@PhoneWearListenerService, incident, file, start.toLong(), end.toLong(), sha)
            }.onFailure { AppLog.warn("Evidence", "Couldn't receive watch audio", it) }
        }
    }

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
            STOP_LIVE -> LiveShare.stop(context, "Stopped from the notification")
            JOURNEY_OK -> Journey.respondOk(context)
            JOURNEY_SOS -> Journey.alert(now = true)
            JOURNEY_ARRIVED -> Journey.arrived(context)
            JOURNEY_END -> Journey.end(context)
        }
    }

    companion object {
        const val SENT = "in.security4her.app.SMS_SENT"
        const val DELIVERED = "in.security4her.app.SMS_DELIVERED"
        const val CANCEL_CALL = "in.security4her.app.CANCEL_CALL"
        const val STOP_LIVE = "in.security4her.app.STOP_LIVE"
        const val JOURNEY_OK = "in.security4her.app.JOURNEY_OK"
        const val JOURNEY_SOS = "in.security4her.app.JOURNEY_SOS"
        const val JOURNEY_ARRIVED = "in.security4her.app.JOURNEY_ARRIVED"
        const val JOURNEY_END = "in.security4her.app.JOURNEY_END"
    }
}

/** Keeps the process alive during an SOS and shows the ongoing notification (type: location). */
class SosService : androidx.lifecycle.LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val n = build(this)
        val wantMic = Evidence.wantsAudio(this)
        val wantCam = Evidence.wantsVideo(this)
        // Microphone and camera count only if Android lets this service hold those types right now
        // (it does when the app is on screen, or when Safe Journey / live sharing already holds them).
        var mic = false
        var cam = false
        val ok = if (Build.VERSION.SDK_INT >= 30) {
            val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            val full = loc or (if (wantMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (wantCam) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
            try {
                startForeground(ID, n, full)
                mic = wantMic
                cam = wantCam
                true
            } catch (e: Exception) {
                if (full != loc) AppLog.warn("Evidence", "Android blocked the microphone/camera for a background start", e)
                runCatching { startForeground(ID, n, loc) }.isSuccess
            }
        } else runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(ID, n)
            mic = wantMic
            cam = wantCam
        }.isSuccess
        if (!ok) {
            Log.w(TAG, "startForeground failed") // e.g. no location permission on API 34+
            runCatching { getSystemService(NotificationManager::class.java).notify(ID, n) }
            stopSelf()
            return START_NOT_STICKY
        }
        val inc = SosEngine.current.value
        if (inc != null && inc.active) {
            Evidence.start(this, this, inc.id, inc.trigger, mic, cam)
            if ((wantMic && !mic) || (wantCam && !cam)) Evidence.askToOpen(this) else Evidence.clearAsk(this)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Evidence.stop(this)
        super.onDestroy()
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
                .setSmallIcon(R.drawable.ic_notif_cat)
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
        AppLog.info("SMS", "Test text to $phone")
        send(ctx, phone, "Security4Her test: if you got this, SOS texts from this phone work.", TEST_ID)?.let {
            _test.value = "Failed: $it"
            AppLog.error("SMS", "Test text failed: $it")
        }
    }

    fun onTestResult(action: String, ok: Boolean, code: Int) {
        _test.value = when {
            action == SosReceiver.DELIVERED && ok -> "Delivered. SOS texts work from this phone."
            action == SosReceiver.DELIVERED -> _test.value
            ok -> "Sent. Waiting for the delivery report."
            else -> "Failed: ${reason(code)}"
        }
        if (ok) AppLog.ok("SMS", "Test text ${if (action == SosReceiver.DELIVERED) "delivered" else "sent"}")
        else AppLog.error("SMS", "Test text failed: ${reason(code)}")
    }
}

/** The phone's normal share sheet (Messages, Gmail, Telegram, anything). */
object ShareSheet {
    fun text(ctx: Context, text: String, title: String = "Share"): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/**
 * Location for SOS and live sharing. While either is on, location runs continuously: Google's fused provider
 * plus Android's own GPS and network providers (the fallback for phones without Google services).
 * Callers read the newest fix; one-shot requests are only used when nothing is tracking.
 */
object Locator {
    private val _latest = MutableStateFlow<Fix?>(null)
    val latest: StateFlow<Fix?> = _latest.asStateFlow()
    private val users = mutableSetOf<String>()
    private var fusedCb: LocationCallback? = null
    private var lmListener: LocationListener? = null

    fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** False when location is switched off in quick settings. */
    fun enabled(ctx: Context): Boolean =
        runCatching { LocationManagerCompat.isLocationEnabled(ctx.getSystemService(LocationManager::class.java)) }.getOrDefault(true)

    @Synchronized
    private fun offer(l: Location, source: String) {
        val f = Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, System.currentTimeMillis())
        val cur = _latest.value
        // A fresh fix wins, unless a much more accurate one arrived in the last 15 s.
        if (cur != null && f.at - cur.at < 15_000 && (f.acc ?: 999f) > (cur.acc ?: 999f) * 2) return
        if (cur == null || f.at - cur.at > 120_000) AppLog.ok("Location", "Fix from $source, accurate to ${f.acc?.toInt() ?: "?"} m")
        _latest.value = f
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context, who: String) {
        val app = ctx.applicationContext
        users += who
        if (fusedCb != null || lmListener != null) return
        if (!allowed(app)) {
            AppLog.error("Location", "Location permission is off. Allow it in Settings → Apps → Security4Her → Permissions.")
            return
        }
        if (!enabled(app)) AppLog.error("Location", "Location is switched off on this phone. Turn it on in quick settings.")
        runCatching {
            val cb = object : LocationCallback() {
                override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { offer(it, "Google location") } }
            }
            LocationServices.getFusedLocationProviderClient(app).requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).setMinUpdateIntervalMillis(4_000).build(),
                cb, Looper.getMainLooper(),
            )
            fusedCb = cb
        }.onFailure { AppLog.warn("Location", "Google location unavailable, using the phone's GPS directly", it) }
        runCatching {
            val lm = app.getSystemService(LocationManager::class.java)
            val l = LocationListener { offer(it, if (it.provider == LocationManager.GPS_PROVIDER) "GPS" else "mobile network") }
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }
            providers.forEach { lm.requestLocationUpdates(it, 10_000L, 0f, l, Looper.getMainLooper()) }
            lmListener = l
        }.onFailure { AppLog.warn("Location", "Phone GPS unavailable", it) }
        AppLog.info("Location", "Tracking on for $who")
    }

    @Synchronized
    fun stop(ctx: Context, who: String) {
        users -= who
        if (users.isNotEmpty()) return
        val app = ctx.applicationContext
        fusedCb?.let { cb -> runCatching { LocationServices.getFusedLocationProviderClient(app).removeLocationUpdates(cb) } }
        lmListener?.let { l -> runCatching { app.getSystemService(LocationManager::class.java).removeUpdates(l) } }
        if (fusedCb != null || lmListener != null) AppLog.info("Location", "Tracking off")
        fusedCb = null
        lmListener = null
    }

    /** Newest fix no older than [maxAgeMs], waiting up to [waitMs] for one to arrive. */
    @SuppressLint("MissingPermission")
    suspend fun fresh(ctx: Context, waitMs: Long = 12_000, maxAgeMs: Long = 60_000): Fix? {
        if (!allowed(ctx)) return null.also { AppLog.warn("Location", "Location permission is off") }
        fun recent() = _latest.value?.takeIf { System.currentTimeMillis() - it.at < maxAgeMs }
        recent()?.let { return it }
        val got = withTimeoutOrNull(waitMs) {
            runCatching {
                LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            }.getOrNull()?.let { offer(it, "Google location") }
            recent() ?: _latest.first { it != null && System.currentTimeMillis() - it.at < maxAgeMs }
        }
        return got ?: recent().also {
            if (it == null) AppLog.warn("Location", if (!enabled(ctx)) "No fix: location is switched off on this phone" else "No fix yet. Indoors or weak GPS?")
        }
    }

    /** Best available old fix: tracked, then Google's last known, then the phone's own providers. */
    @SuppressLint("MissingPermission")
    suspend fun last(ctx: Context): Fix? {
        _latest.value?.let { return it }
        if (!allowed(ctx)) return null
        runCatching { LocationServices.getFusedLocationProviderClient(ctx).lastLocation.await() }.getOrNull()
            ?.let { return Fix(it.latitude, it.longitude, it.accuracy, it.time) }
        val lm = ctx.getSystemService(LocationManager::class.java)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { Fix(it.latitude, it.longitude, it.accuracy, it.time) }
    }
}

/**
 * Live location over ntfy.sh: a free relay that needs no account or key. Each SOS or live share gets a
 * random, unguessable topic; the link opens a page that shows every update as it arrives.
 */
object Live {
    private const val SERVER = "https://ntfy.sh"

    fun newTopic(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return "s4h-" + (1..22).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** Our own map page when it's hosted (LIVE_PAGE_URL), otherwise ntfy's page for the topic. */
    fun url(topic: String) = BuildConfig.LIVE_PAGE_URL.takeIf { it.isNotBlank() }?.let { "$it?t=$topic" } ?: "$SERVER/$topic"

    fun publish(topic: String, title: String, message: String, click: String?, tags: List<String>, priority: Int): Boolean = try {
        val body = buildJsonObject {
            put("topic", topic)
            put("title", title)
            put("message", message)
            put("priority", priority)
            putJsonArray("tags") { tags.forEach { add(it) } }
            if (click != null) {
                put("click", click)
                putJsonArray("actions") {
                    addJsonObject { put("action", "view"); put("label", "Open in Google Maps"); put("url", click) }
                }
            }
        }.toString()
        val c = (URL(SERVER).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        c.disconnect()
        if (code != 200) error("HTTP $code")
        AppLog.ok("Live", "Posted: $message")
        true
    } catch (e: Exception) {
        AppLog.warn("Live", "Couldn't post live location", e)
        false
    }
}

data class LiveSession(val topic: String, val url: String, val startedAt: Long, val until: Long, val pushes: Int = 0, val lastAt: Long? = null, val failing: Boolean = false)

/** "Share live location" without an SOS, e.g. while walking home. Posts every 20 s until stopped or the time runs out. */
object LiveShare {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _session = MutableStateFlow<LiveSession?>(null)
    val session: StateFlow<LiveSession?> = _session.asStateFlow()
    private var job: Job? = null

    fun start(ctx: Context, minutes: Int = 60): LiveSession {
        _session.value?.let { return it }
        val app = ctx.applicationContext
        val topic = Live.newTopic()
        val now = System.currentTimeMillis()
        val s = LiveSession(topic, Live.url(topic), now, now + minutes * 60_000L)
        _session.value = s
        AppLog.ok("Live", "Sharing live location for $minutes min: ${s.url}")
        Locator.start(app, "live")
        runCatching { ContextCompat.startForegroundService(app, Intent(app, LiveService::class.java)) }
            .onFailure { AppLog.warn("Live", "Background service blocked; sharing only while the app is open", it) }
        job = scope.launch {
            val name = runCatching { Backend.profile().displayName }.getOrNull() ?: "Your contact"
            var first = true
            while (true) {
                val cur = _session.value ?: break
                if (System.currentTimeMillis() > cur.until) {
                    stop(app, "Time's up")
                    break
                }
                val f = Locator.fresh(app, waitMs = 10_000) ?: Locator.last(app)
                val ok = if (f != null) {
                    Live.publish(
                        topic, if (first) "$name is sharing live location" else "$name's live location",
                        "%.6f, %.6f, accurate to %s m, at %s".format(Locale.US, f.lat, f.lng, f.acc?.toInt() ?: "?", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(f.at))),
                        "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, f.lat, f.lng), listOf("round_pushpin"), if (first) 4 else 2,
                    )
                } else false
                _session.value = _session.value?.let { it.copy(pushes = it.pushes + if (ok) 1 else 0, lastAt = if (ok) System.currentTimeMillis() else it.lastAt, failing = !ok) }
                first = false
                LiveService.refresh(app)
                delay(20_000)
            }
        }
        return s
    }

    fun stop(ctx: Context, why: String = "Stopped") {
        val s = _session.value ?: return
        _session.value = null
        job?.cancel()
        AppLog.info("Live", "Live sharing ended: $why")
        scope.launch { Live.publish(s.topic, "Live location sharing ended", why, null, listOf("white_check_mark"), 3) }
        ctx.applicationContext.stopService(Intent(ctx.applicationContext, LiveService::class.java))
        Locator.stop(ctx, "live")
    }
}

/** Keeps live sharing running with the screen off, with a Stop button in the notification. */
class LiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Evidence.holdForeground(this, ID, build(this))
        } catch (e: Exception) {
            AppLog.warn("Live", "Couldn't keep sharing in the background", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 113
        private const val CHANNEL = "live"

        fun refresh(ctx: Context) {
            if (LiveShare.session.value == null) return
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx)) }
        }

        private fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live location", NotificationManager.IMPORTANCE_LOW))
            }
            val s = LiveShare.session.value
            val open = PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getBroadcast(ctx, 11, Intent(ctx, SosReceiver::class.java).setAction(SosReceiver.STOP_LIVE), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif_cat)
                .setContentTitle("Sharing your live location")
                .setContentText(if (s == null) "" else "${s.pushes} ${if (s.pushes == 1) "update" else "updates"} sent" + if (s.failing) ", no internet right now" else "")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, "Stop sharing", stop)
                .apply { if (s != null) setWhen(s.until).setUsesChronometer(true).setChronometerCountDown(true) }
                .build()
        }
    }
}

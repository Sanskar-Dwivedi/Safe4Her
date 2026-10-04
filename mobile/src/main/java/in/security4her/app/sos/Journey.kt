package `in`.security4her.app.sos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import `in`.security4her.app.MainActivity
import `in`.security4her.app.R
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Backend
import `in`.security4her.app.core.S4hJson
import `in`.security4her.app.core.Settings
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class Destination(val name: String, val detail: String, val lat: Double, val lng: Double)

data class TravelMode(val key: String, val label: String)

data class RouteEstimate(val distanceM: Double, val minutes: Int, val fromRoute: Boolean)

@Serializable
data class JourneyState(
    val id: String,
    val dest: Destination,
    val mode: String,
    val startedAt: Long,
    val etaMin: Int,
    val deadline: Long,
    val totalM: Double,
    val startM: Double? = null, // straight-line distance at the start, the baseline for progress
    val liveTopic: String,
    val liveUrl: String,
    val status: String = "active", // active | checking | arrived | alerted | ended
    val checkReason: String? = null, // late | stuck | away
    val checkUntil: Long? = null,
    val remainingM: Double? = null,
    val minRemainingM: Double? = null,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastFixAt: Long? = null,
    val anchorLat: Double? = null,
    val anchorLng: Double? = null,
    val stillSince: Long? = null,
    val endedAt: Long? = null,
) {
    val running get() = status == "active" || status == "checking"
}

/**
 * Safe Journey (free for everyone): she says where she's going; we estimate the trip, watch it, and if she's late,
 * stops moving, or heads away from the destination, we ask if she's OK. No answer → her contacts get an SOS.
 */
object Journey {
    val modes = listOf(
        TravelMode("walk", "Walking"), TravelMode("scooter", "Scooter"), TravelMode("car", "Car / auto"), TravelMode("bus", "Bus"),
    )
    const val ARRIVE_M = 150.0
    private const val TICK_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<JourneyState?>(null)
    val state: StateFlow<JourneyState?> = _state.asStateFlow()
    private var job: Job? = null
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        _state.value = Store.prefs(app).getString("journey", null)
            ?.let { runCatching { S4hJson.decodeFromString(JourneyState.serializer(), it) }.getOrNull() }
        if (_state.value?.running == true) {
            AppLog.info("Journey", "Resuming the journey to ${_state.value?.dest?.name} after a restart")
            Locator.start(app, "journey")
            runCatching { ContextCompat.startForegroundService(app, Intent(app, JourneyService::class.java)) }
            loop()
        }
    }

    fun tellOnStart(ctx: Context) = Store.prefs(ctx).getBoolean("journey_tell_start", true)
    fun setTellOnStart(ctx: Context, on: Boolean) = Store.prefs(ctx).edit().putBoolean("journey_tell_start", on).apply()
    fun tellOnArrive(ctx: Context) = Store.prefs(ctx).getBoolean("journey_tell_arrive", true)
    fun setTellOnArrive(ctx: Context, on: Boolean) = Store.prefs(ctx).edit().putBoolean("journey_tell_arrive", on).apply()

    /** Test Mode shortens the timers so the whole flow fits in a demo. */
    private fun test() = Settings.load(app).testMode
    fun stillLimitMs(ctx: Context) = if (Settings.load(ctx).testMode) 60_000L else 10 * 60_000L
    fun checkWindowMs(ctx: Context) = if (Settings.load(ctx).testMode) 30_000L else 2 * 60_000L

    // ---- recent places ----
    private val destList = ListSerializer(Destination.serializer())
    fun recents(ctx: Context): List<Destination> =
        Store.prefs(ctx).getString("journey_recent", null)?.let { runCatching { S4hJson.decodeFromString(destList, it) }.getOrNull() }.orEmpty()

    private fun remember(ctx: Context, d: Destination) {
        val list = (listOf(d) + recents(ctx).filter { it.name != d.name || meters(it.lat, it.lng, d.lat, d.lng) > 100 }).take(5)
        Store.prefs(ctx).edit().putString("journey_recent", S4hJson.encodeToString(destList, list)).apply()
    }

    // ---- search + routing (OpenStreetMap services, no key) ----
    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Security4Her/0.3 (safety app)")
        }
        if (c.responseCode != 200) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    suspend fun search(query: String, near: Fix?): List<Destination> = withContext(Dispatchers.IO) {
        val bias = near?.let {
            "&viewbox=%.4f,%.4f,%.4f,%.4f".format(Locale.US, it.lng - 0.4, it.lat + 0.4, it.lng + 0.4, it.lat - 0.4)
        }.orEmpty()
        val body = get("https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(query, "UTF-8")}&format=jsonv2&limit=8&addressdetails=0$bias")
        Json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val full = o["display_name"]?.jsonPrimitive?.content.orEmpty()
            val parts = full.split(", ")
            val name = o["name"]?.jsonPrimitive?.content?.ifBlank { null } ?: parts.first()
            Destination(name, parts.drop(1).take(3).joinToString(", "), lat, lng)
        }.let { list -> if (near == null) list else list.sortedBy { meters(near.lat, near.lng, it.lat, it.lng) } }
    }

    /** Address for a point picked on the map; falls back to the coordinates. */
    suspend fun reverse(lat: Double, lng: Double): Destination = withContext(Dispatchers.IO) {
        runCatching {
            val o = Json.parseToJsonElement(
                get("https://nominatim.openstreetmap.org/reverse?lat=%.6f&lon=%.6f&format=jsonv2&zoom=18".format(Locale.US, lat, lng)),
            ).jsonObject
            val parts = o["display_name"]!!.jsonPrimitive.content.split(", ")
            val name = o["name"]?.jsonPrimitive?.content?.ifBlank { null } ?: parts.take(2).joinToString(", ")
            Destination(name, parts.filter { it != name }.take(3).joinToString(", "), lat, lng)
        }.getOrElse {
            AppLog.warn("Journey", "Couldn't look up the address for the pinned spot", it)
            Destination("Pinned place", "%.5f, %.5f".format(Locale.US, lat, lng), lat, lng)
        }
    }

    /**
     * Road route from OpenStreetMap's routing service, with Indian city traffic factored in
     * (its car times assume empty roads). Falls back to straight-line distance and typical speeds.
     */
    suspend fun route(from: Fix, to: Destination, mode: String): RouteEstimate = withContext(Dispatchers.IO) {
        val profile = if (mode == "walk") "routed-foot" else "routed-car"
        val factor = when (mode) { "walk" -> 1.0; "scooter" -> 1.6; "car" -> 2.0; else -> 2.6 }
        runCatching {
            val url = "https://routing.openstreetmap.de/$profile/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false"
                .format(Locale.US, from.lng, from.lat, to.lng, to.lat)
            val r = Json.parseToJsonElement(get(url)).jsonObject["routes"]!!.jsonArray[0].jsonObject
            val dist = r["distance"]!!.jsonPrimitive.content.toDouble()
            val secs = r["duration"]!!.jsonPrimitive.content.toDouble()
            RouteEstimate(dist, max(1, ceil(secs * factor / 60).toInt()), true)
        }.getOrElse {
            AppLog.warn("Journey", "Routing service unavailable, using a straight-line estimate", it)
            val d = meters(from.lat, from.lng, to.lat, to.lng) * 1.35
            val kmh = when (mode) { "walk" -> 4.5; "scooter" -> 20.0; "car" -> 18.0; else -> 14.0 }
            RouteEstimate(d, max(1, ceil(d / 1000 / kmh * 60).toInt()), false)
        }
    }

    /** The time she's given: the estimate plus a quarter, plus 5 minutes. */
    fun allowedMinutes(etaMin: Int) = ceil(etaMin * 1.25 + 5).toInt()

    // ---- lifecycle ----
    fun start(ctx: Context, dest: Destination, mode: String, est: RouteEstimate, allowMin: Int, tellContacts: Boolean): JourneyState {
        app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val topic = Live.newTopic()
        val here = Locator.latest.value
        val s = JourneyState(
            id = UUID.randomUUID().toString(), dest = dest, mode = mode, startedAt = now, etaMin = est.minutes,
            deadline = now + allowMin * 60_000L, totalM = est.distanceM, liveTopic = topic, liveUrl = Live.url(topic),
            remainingM = here?.let { meters(it.lat, it.lng, dest.lat, dest.lng) },
            startM = here?.let { meters(it.lat, it.lng, dest.lat, dest.lng) },
            minRemainingM = here?.let { meters(it.lat, it.lng, dest.lat, dest.lng) },
            anchorLat = here?.lat, anchorLng = here?.lng, stillSince = now,
        )
        set(s)
        remember(app, dest)
        setTellOnStart(app, tellContacts)
        AppLog.ok("Journey", "Started to ${dest.name} by ${label(mode)}: about ${est.minutes} min, check-in if not there by ${hhmm(s.deadline)}")
        Locator.start(app, "journey")
        runCatching { ContextCompat.startForegroundService(app, Intent(app, JourneyService::class.java)) }
            .onFailure { AppLog.warn("Journey", "Background service blocked; the journey is watched only while the app is open", it) }
        scope.launch {
            val name = userName()
            Live.publish(topic, "$name is sharing live location", "On the way to ${dest.name}, expected by ${hhmm(s.deadline)}.", null, listOf("walking"), 3)
            if (tellContacts) text(
                "$name is heading to ${dest.name} and should arrive by ${hhmm(s.deadline)}. Follow live: ${s.liveUrl} " +
                    "You'll get an alert if she doesn't arrive. (Security4Her)",
            )
        }
        loop()
        return s
    }

    private fun loop() {
        job?.cancel()
        job = scope.launch {
            while (true) {
                val s = _state.value ?: break
                if (!s.running) break
                runCatching { tick() }.onFailure { AppLog.warn("Journey", "Check failed", it) }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun tick() {
        var s = _state.value ?: return
        val now = System.currentTimeMillis()
        if (s.status == "checking") {
            if (now >= (s.checkUntil ?: now)) alert()
            else JourneyService.refresh(app)
            return
        }
        val f = Locator.fresh(app, waitMs = 8_000)
        if (f != null) {
            val rem = meters(f.lat, f.lng, s.dest.lat, s.dest.lng)
            val moved = s.anchorLat == null || meters(f.lat, f.lng, s.anchorLat!!, s.anchorLng!!) > 75
            s = s.copy(
                lastLat = f.lat, lastLng = f.lng, lastFixAt = f.at, remainingM = rem,
                minRemainingM = minOf(rem, s.minRemainingM ?: rem),
                startM = s.startM ?: rem,
                anchorLat = if (moved) f.lat else s.anchorLat, anchorLng = if (moved) f.lng else s.anchorLng,
                stillSince = if (moved) now else s.stillSince ?: now,
            )
            set(s)
            Live.publish(
                s.liveTopic, "${userName()}'s live location",
                "%.6f, %.6f, accurate to %s m, at %s".format(Locale.US, f.lat, f.lng, f.acc?.toInt() ?: "?", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(f.at))),
                "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, f.lat, f.lng), listOf("round_pushpin"), 2,
            )
            if (rem <= ARRIVE_M) {
                arrived(auto = true)
                return
            }
        }
        val rem = s.remainingM
        when {
            now > s.deadline -> check("late")
            s.stillSince != null && now - s.stillSince!! > stillLimitMs(app) && (rem == null || rem > 300) -> check("stuck")
            rem != null && s.minRemainingM != null && rem > s.minRemainingM!! + max(1_000.0, 0.3 * s.totalM) -> check("away")
            else -> JourneyService.refresh(app)
        }
    }

    private fun check(reason: String) {
        val s = _state.value ?: return
        val until = System.currentTimeMillis() + checkWindowMs(app)
        set(s.copy(status = "checking", checkReason = reason, checkUntil = until))
        AppLog.warn("Journey", "Check-in: ${reasonShort(reason, s)}. Alerting contacts at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(until))} if there's no answer.")
        JourneyService.showCheckIn(app)
    }

    /** She answered "I'm OK": give her more time, or reset the stopped-moving timer. */
    fun respondOk(ctx: Context) {
        val s = _state.value ?: return
        if (s.status != "checking") return
        val now = System.currentTimeMillis()
        set(
            s.copy(
                status = "active", checkReason = null, checkUntil = null,
                deadline = if (s.checkReason == "late") max(s.deadline, now) + 10 * 60_000L else s.deadline,
                stillSince = now, anchorLat = s.lastLat ?: s.anchorLat, anchorLng = s.lastLng ?: s.anchorLng,
                minRemainingM = if (s.checkReason == "away") s.remainingM else s.minRemainingM,
            ),
        )
        AppLog.ok("Journey", "She answered the check-in: I'm OK" + if (s.checkReason == "late") ". 10 more minutes added." else "")
        JourneyService.clearCheckIn(ctx)
        JourneyService.refresh(ctx)
    }

    fun addTime(ctx: Context, minutes: Int) {
        val s = _state.value ?: return
        if (!s.running) return
        set(s.copy(deadline = max(s.deadline, System.currentTimeMillis()) + minutes * 60_000L))
        AppLog.info("Journey", "$minutes more minutes. New arrive-by time ${hhmm(_state.value!!.deadline)}")
        JourneyService.refresh(ctx)
    }

    fun arrived(ctx: Context = app, auto: Boolean = false) {
        val s = _state.value ?: return
        if (!s.running) return
        finish("arrived")
        AppLog.ok("Journey", if (auto) "Arrived at ${s.dest.name} (detected automatically)" else "She marked herself arrived at ${s.dest.name}")
        scope.launch {
            val name = userName()
            Live.publish(s.liveTopic, "$name is safe now", "Arrived at ${s.dest.name}.", null, listOf("white_check_mark"), 3)
            if (tellOnArrive(ctx)) text("$name reached ${s.dest.name} safely at ${hhmm(System.currentTimeMillis())}. (Security4Her)")
        }
        JourneyService.notifyArrived(ctx, s.dest.name)
    }

    fun end(ctx: Context = app) {
        val s = _state.value ?: return
        if (!s.running) return
        finish("ended")
        AppLog.info("Journey", "Journey to ${s.dest.name} ended by her")
        scope.launch { Live.publish(s.liveTopic, "${userName()} is safe now", "She ended the journey.", null, listOf("white_check_mark"), 3) }
    }

    /** No answer to the check-in, or she tapped "Send SOS now". */
    fun alert(now: Boolean = false) {
        val s = _state.value ?: return
        if (!s.running) return
        val why = if (now) "She pressed SOS during her journey to ${s.dest.name}." else reasonForContacts(s)
        finish("alerted")
        AppLog.error("Journey", "Alerting contacts: $why")
        val fix = Locator.latest.value?.takeIf { System.currentTimeMillis() - it.at < 5 * 60_000 }
            ?: s.lastLat?.let { Fix(it, s.lastLng!!, null, s.lastFixAt ?: System.currentTimeMillis()) }
        SosEngine.start(app, trigger = "journey", source = "phone", fix = fix, reason = why)
    }

    fun dismiss() {
        if (_state.value?.running == true) return
        _state.value = null
        Store.prefs(app).edit().remove("journey").apply()
    }

    private fun finish(status: String) {
        val s = _state.value ?: return
        job?.cancel()
        set(s.copy(status = status, endedAt = System.currentTimeMillis(), checkUntil = null))
        JourneyService.clearCheckIn(app)
        app.stopService(Intent(app, JourneyService::class.java))
        Locator.stop(app, "journey")
    }

    // ---- helpers ----
    private fun set(s: JourneyState) {
        _state.value = s
        Store.prefs(app).edit().putString("journey", S4hJson.encodeToString(JourneyState.serializer(), s)).apply()
    }

    private suspend fun text(body: String) {
        val settings = Settings.load(app)
        val contacts = if (settings.testMode) emptyList() else
            runCatching { Backend.contacts() }.getOrDefault(emptyList()).filter { it.receivesSos }.map { it.phone }
        val recipients = journeyRecipients(settings.testMode, settings.demoNumber, contacts)
        val tagged = body + if (settings.testMode) " [TEST]" else ""
        recipients.forEach { number -> Sms.send(app, number, tagged, "journey")?.let { AppLog.warn("Journey", "Journey text failed: $it") } }
        if (recipients.isEmpty()) AppLog.warn("Journey", "No eligible journey text recipients. Check the test number or contacts.")
        else AppLog.info("Journey", "Requested ${recipients.size} journey texts; delivery is not confirmed.")
    }

    private suspend fun userName() = runCatching { Backend.profile().displayName }.getOrNull() ?: "Your contact"

    fun label(mode: String) = modes.firstOrNull { it.key == mode }?.label?.lowercase() ?: mode
    fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))

    fun reasonShort(reason: String?, s: JourneyState) = when (reason) {
        "late" -> "not at ${s.dest.name} by ${hhmm(s.deadline)}"
        "stuck" -> "stopped moving for ${stillLimitMs(app) / 60_000} min"
        "away" -> "moving away from ${s.dest.name}"
        else -> "check-in"
    }

    private fun reasonForContacts(s: JourneyState) = when (s.checkReason) {
        "late" -> "She was going to ${s.dest.name}, hasn't arrived by ${hhmm(s.deadline)}, and didn't answer a check-in."
        "stuck" -> "She stopped moving on the way to ${s.dest.name} and didn't answer a check-in."
        "away" -> "She was heading away from ${s.dest.name} and didn't answer a check-in."
        else -> "She didn't answer a Safe Journey check-in on the way to ${s.dest.name}."
    }

    fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * 6_371_000.0 * atan2(sqrt(h), sqrt(1 - h))
    }
}

/** Keeps the journey watched with the screen off; shows the check-in alarm when it's time. */
class JourneyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Evidence.holdForeground(this, ID, ongoing(this))
        } catch (e: Exception) {
            AppLog.warn("Journey", "Couldn't keep watching in the background", e)
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val ID = 114
        private const val CHECK_ID = 115
        private const val DONE_ID = 116
        private const val CHANNEL = "journey"
        private const val CHANNEL_ALERT = "journey_check"

        private fun channels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Safe Journey", NotificationManager.IMPORTANCE_LOW))
            if (nm.getNotificationChannel(CHANNEL_ALERT) == null) nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Safe Journey check-ins", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                },
            )
        }

        private fun open(ctx: Context, code: Int) = PendingIntent.getActivity(
            ctx, code, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun action(ctx: Context, code: Int, action: String) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, SosReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )

        fun ongoing(ctx: Context): Notification {
            channels(ctx)
            val s = Journey.state.value
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif_security4her)
                .setContentTitle(if (s == null) "Safe Journey" else "On the way to ${s.dest.name}")
                .setContentText(
                    if (s == null) "" else "Arrive by ${Journey.hhmm(s.deadline)}" +
                        (s.remainingM?.let { " · " + if (it < 1000) "${it.toInt()} m to go" else "%.1f km to go".format(Locale.US, it / 1000) } ?: ""),
                )
                .setOngoing(true)
                .setContentIntent(open(ctx, 20))
                .addAction(0, "I've arrived", action(ctx, 21, SosReceiver.JOURNEY_ARRIVED))
                .addAction(0, "End journey", action(ctx, 22, SosReceiver.JOURNEY_END))
            if (s != null) b.setWhen(s.deadline).setUsesChronometer(true).setChronometerCountDown(true)
            return b.build()
        }

        fun refresh(ctx: Context) {
            if (Journey.state.value?.running != true) return
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, ongoing(ctx)) }
        }

        fun showCheckIn(ctx: Context) {
            channels(ctx)
            val s = Journey.state.value ?: return
            // With a PIN set, "I'm OK" must go through the app so someone else can't dismiss it.
            val ok = if (`in`.security4her.app.core.Pin.isSet(ctx)) open(ctx, 23) else action(ctx, 23, SosReceiver.JOURNEY_OK)
            val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notif_security4her)
                .setContentTitle("Are you OK?")
                .setContentText("${Journey.reasonShort(s.checkReason, s).replaceFirstChar { it.uppercase() }}. Answer or your contacts get an SOS.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVibrate(longArrayOf(0, 600, 300, 600, 300, 600))
                .setOngoing(true)
                .setContentIntent(open(ctx, 24))
                .addAction(0, "I'm OK", ok)
                .addAction(0, "Send SOS now", action(ctx, 25, SosReceiver.JOURNEY_SOS))
                .apply { s.checkUntil?.let { setWhen(it).setUsesChronometer(true).setChronometerCountDown(true) } }
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(CHECK_ID, n) }
            refresh(ctx)
        }

        fun clearCheckIn(ctx: Context) {
            runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(CHECK_ID) }
        }

        fun notifyArrived(ctx: Context, place: String) {
            channels(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif_security4her)
                .setContentTitle("You've reached $place")
                .setContentText("Safe Journey is over. Live sharing stopped.")
                .setAutoCancel(true)
                .setContentIntent(open(ctx, 26))
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(DONE_ID, n) }
        }
    }
}

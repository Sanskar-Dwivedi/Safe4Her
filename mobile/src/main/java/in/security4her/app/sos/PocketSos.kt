package `in`.security4her.app.sos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import `in`.security4her.app.MainActivity
import `in`.security4her.app.R
import `in`.security4her.app.core.AppLog
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
import kotlin.math.sqrt

// ---------------------------------------------------------------- Pocket SOS
// Three sharp knocks on the phone through a pocket or bag start a 5-second countdown, then a normal SOS.
// Only listens while the screen is off, so tapping and typing on the screen can never trigger it.

/**
 * Finds three deliberate knocks in accelerometer data.
 *
 * A knock is a sudden change in acceleration, so the detector watches the jump between one reading and the next
 * (m/s^2 per sample). Slow movement (holding the phone, walking, turning) changes little from sample to sample;
 * a knock's hit and ring change a lot. Each jump above [FLOOR] opens an 80 ms window; its peak decides whether it's a
 * knock (at or above [threshold]) or too soft. Nothing is counted for 200 ms after a knock, and by then the phone must
 * have settled (a shove or a fall keeps moving). The threshold rises with ongoing movement, so she can knock while
 * walking. Exactly three knocks, 0.2-1.1 s apart, then 0.7 s with no fourth; longer runs (running, bumps) are rejected.
 *
 * A phone lying flat and dead still, with nothing over its screen, is on a table, not in a pocket: a hard surface turns
 * even a light tap into a sharp spike, so a sequence that starts there needs knocks [TABLE_FACTOR] times as hard. When
 * the proximity sensor says the screen is covered ([covered]), it's in a pocket or bag and normal knocks count.
 */
class KnockDetector(
    @Volatile var threshold: Float = DEFAULT_THRESHOLD,
    private val onKnock: (count: Int, strength: Float) -> Unit = { _, _ -> },
    private val onSoft: (strength: Float) -> Unit = {},
    private val onReject: (reason: Reject) -> Unit = {},
    private val onTriple: () -> Unit,
) {
    enum class Reject { TooSlow, TooMany, Moving }

    private enum class Phase { Idle, Peak, Settle }

    private val prev = FloatArray(3)
    private var primed = false
    private var lastT = 0L
    private var activity = 0f // average ongoing movement
    private var recent = 0f // movement over the last ~50 ms
    private var phase = Phase.Idle
    private var crossT = 0L
    private var peak = 0f
    private var bar = 0f // threshold in force when the jolt started
    private val knocks = ArrayList<Long>(4)
    private var fireAt = 0L
    private var blockedUntil = 0L
    private val grav = FloatArray(3) // slow average of the readings: which way is down
    private var stillSince = 0L // when the phone last stopped moving
    private var onTable = false // the current sequence started with the phone resting flat

    /** From the proximity sensor: true in a pocket or bag, false in the open, null when the phone can't tell. */
    @Volatile var covered: Boolean? = null

    /** True while one or two knocks have been counted and the rest may follow. */
    val inProgress get() = knocks.isNotEmpty() || fireAt != 0L

    /** The latest jump, for a live meter. */
    @Volatile var level = 0f
        private set

    /** Drop a jolt in progress (e.g. while the phone itself is vibrating). */
    fun cancelJolt() { phase = Phase.Idle }

    fun reset() { knocks.clear(); fireAt = 0L; phase = Phase.Idle }

    /** [t] in milliseconds. */
    fun feed(x: Float, y: Float, z: Float, t: Long) {
        if (!primed) { prev[0] = x; prev[1] = y; prev[2] = z; grav[0] = x; grav[1] = y; grav[2] = z; primed = true; lastT = t; stillSince = t; return }
        val dt = (t - lastT).coerceIn(1, 100).toFloat()
        lastT = t
        val dx = x - prev[0]; val dy = y - prev[1]; val dz = z - prev[2]
        prev[0] = x; prev[1] = y; prev[2] = z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        level = d
        recent += (d - recent) * dt / (50f + dt)
        val g = dt / (400f + dt)
        grav[0] += (x - grav[0]) * g; grav[1] += (y - grav[1]) * g; grav[2] += (z - grav[2]) * g

        if (fireAt != 0L && t >= fireAt) { fireAt = 0L; knocks.clear(); onTriple(); return }
        if (fireAt == 0L && knocks.isNotEmpty() && t - knocks.last() > MAX_GAP) { knocks.clear(); onReject(Reject.TooSlow) }

        when (phase) {
            Phase.Idle -> {
                val resting = resting(t)
                activity += (d - activity) * dt / (500f + dt)
                if (activity > STILL) stillSince = t
                if (knocks.isEmpty() && fireAt == 0L) onTable = resting && covered != true
                val b = maxOf(threshold * (if (onTable) TABLE_FACTOR else 1f), activity * 4f)
                if (d > maxOf(FLOOR, activity * 2.5f, b * 0.35f)) { phase = Phase.Peak; crossT = t; peak = d; bar = b }
            }
            Phase.Peak -> {
                if (d > peak) peak = d
                if (t - crossT >= 80) {
                    if (peak >= bar) { phase = Phase.Settle; knock(crossT, peak) }
                    else { onSoft(peak); phase = Phase.Idle }
                }
            }
            Phase.Settle -> if (t - crossT >= 200) {
                if (recent > bar * 0.4f) { // still shaking: a shove or a fall, not a knock
                    knocks.clear(); fireAt = 0L; blockedUntil = t + 1_000; onReject(Reject.Moving)
                }
                phase = Phase.Idle
            }
        }
    }

    /** Flat (screen up or down) and without even a hand's tremor for 2 s. */
    private fun resting(t: Long): Boolean {
        val flat = kotlin.math.abs(grav[2]) > 8.8f && sqrt(grav[0] * grav[0] + grav[1] * grav[1]) < 3.5f
        return flat && activity < STILL && t - stillSince > 2_000
    }

    private fun knock(t: Long, strength: Float) {
        if (t < blockedUntil) { blockedUntil = t + 1_000; return }
        if (fireAt != 0L) { // a fourth knock: a rhythm, not the signal
            knocks.clear(); fireAt = 0L; blockedUntil = t + 1_200; onReject(Reject.TooMany); return
        }
        knocks.lastOrNull()?.let { if (t - it > MAX_GAP) knocks.clear() }
        knocks += t
        onKnock(knocks.size, strength)
        if (knocks.size == 3) fireAt = t + 700
    }

    companion object {
        const val DEFAULT_THRESHOLD = 4.5f
        /** Smallest jump shown as a (too soft) knock. */
        const val FLOOR = 1.0f
        private const val MAX_GAP = 1_100L
        /** Movement (m/s^2 per sample) below which the phone is lying still: under a hand's tremor, above sensor noise. */
        private const val STILL = 0.12f
        const val TABLE_FACTOR = 2.2f
    }
}

/**
 * Three presses of the power button, about one a second. Android tells apps nothing about the button itself, only
 * that the screen turned on or off, and each press flips it: so 3 flips, each at most 2 s after the last, within 6 s.
 * The calm rhythm matters: the phone's own Emergency SOS (and the double-press camera) only count presses that come
 * within about half a second of each other (Android's GestureLauncherService), so a press a second never reaches it.
 * It arms on the third press at once, and presses in the next 3 s are part of the same run. Slower flips (checking
 * the time, a notification, the screen timing out) never add up.
 */
class PowerPressDetector(private val onTriple: () -> Unit) {
    private val presses = ArrayList<Long>(4)
    private var quietUntil = 0L

    /** Presses counted so far in the current run. */
    val count get() = presses.size

    fun reset() = presses.clear()

    /** [t] in milliseconds, when the screen turned on or off. */
    fun press(t: Long) {
        if (t < quietUntil) return // the rest of a run that already armed it
        presses.lastOrNull()?.let { last ->
            if (t - last < MIN_GAP) return // the very same event delivered twice
            if (t - last > MAX_GAP) presses.clear()
        }
        presses += t
        if (presses.size >= 3 && t - presses.first() <= WINDOW) {
            presses.clear(); quietUntil = t + COOLDOWN
            onTriple()
        }
    }

    /** Forgets an unfinished run once it has gone quiet. */
    fun tick(t: Long) {
        val last = presses.lastOrNull() ?: return
        if (t - last > MAX_GAP) presses.clear()
    }

    companion object {
        // Android delivers screen on/off late and sometimes bunched (a real on/off pair can arrive 70 ms apart),
        // so only exact duplicates are dropped
        const val MIN_GAP = 20L
        const val MAX_GAP = 2_000L
        const val WINDOW = 6_000L
        const val COOLDOWN = 3_000L
    }
}

object PocketSos {
    private const val PREF = "pocket_sos"
    private const val COUNTDOWN_S = 5
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** True while the practice screen is open: knocks are counted there and never start an SOS. */
    @Volatile var practicing = false

    private val _countdown = MutableStateFlow<Int?>(null)
    /** Seconds left before a pocket-triggered SOS; null when nothing is pending. */
    val countdown: StateFlow<Int?> = _countdown.asStateFlow()

    fun enabled(ctx: Context) = Store.prefs(ctx).getBoolean(PREF, false)

    /** How Pocket SOS is set off: "power" (press the power button 3 times, the default) or "knock". */
    fun mode(ctx: Context): String = Store.prefs(ctx).getString("pocket_mode", "power")!!
    fun powerMode(ctx: Context) = mode(ctx) != "knock"

    fun setMode(ctx: Context, m: String) {
        Store.prefs(ctx).edit().putString("pocket_mode", m).apply()
        AppLog.info("Pocket", if (m == "knock") "Pocket SOS now listens for 3 knocks" else "Pocket SOS now listens for 3 power-button presses")
        val i = Intent(ctx, PocketSosService::class.java)
        if (enabled(ctx)) { ctx.stopService(i); runCatching { ContextCompat.startForegroundService(ctx, i) } }
    }

    /** Ends of the screen flips we cause ourselves (waking the screen for the countdown) are not presses. */
    @Volatile var ignoreScreenUntil = 0L

    // ---- screen-off test: the real thing (screen off, background service), except it never starts an SOS ----

    /** What the phone felt during a screen-off test. */
    data class ScreenOffTest(
        val until: Long, // elapsedRealtime when the test window closes
        val screenWentOff: Boolean = false,
        val offMs: Long = 0, // how long the screen was off
        val samples: Int = 0, // motion readings received while it was off
        val knocks: Int = 0, // most knocks counted in one go
        val soft: Int = 0, // jolts felt but too soft to count
        val strongest: Float = 0f,
        val reject: KnockDetector.Reject? = null,
        val passed: Boolean = false,
    ) {
        val rateHz get() = if (offMs > 0) (samples * 1000L / offMs).toInt() else 0
    }

    private val _test = MutableStateFlow<ScreenOffTest?>(null)
    val test: StateFlow<ScreenOffTest?> = _test.asStateFlow()

    fun testing() = _test.value?.let { !it.passed && android.os.SystemClock.elapsedRealtime() < it.until } == true

    internal fun updateTest(f: (ScreenOffTest) -> ScreenOffTest) { _test.value?.let { _test.value = f(it) } }

    fun startTest(ctx: Context) {
        _test.value = ScreenOffTest(until = android.os.SystemClock.elapsedRealtime() + 90_000)
        AppLog.info("Pocket", "Screen-off knock test started")
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PocketSosService::class.java)) }
            .onFailure { AppLog.warn("Pocket", "Couldn't start knock detection", it) }
    }

    fun endTest(ctx: Context) {
        _test.value = null
        if (!enabled(ctx)) ctx.stopService(Intent(ctx, PocketSosService::class.java))
    }

    // ---- how hard a knock must be (m/s^2 of jolt) ----
    enum class Sensitivity(val label: String, val threshold: Float) { High("Light", 2.8f), Normal("Normal", 4.5f), Low("Firm", 7.5f) }

    @Volatile var threshold = KnockDetector.DEFAULT_THRESHOLD
        private set

    /** Nothing is ever more sensitive than Light: below it, ordinary bumps in a pocket start counting as knocks. */
    private const val MIN_THRESHOLD = 2.8f

    fun loadThreshold(ctx: Context): Float {
        threshold = Store.prefs(ctx).getFloat("pocket_threshold", KnockDetector.DEFAULT_THRESHOLD).coerceAtLeast(MIN_THRESHOLD)
        return threshold
    }

    /** True once she has tuned the strength to her own knocks (or picked one). */
    fun calibrated(ctx: Context) = Store.prefs(ctx).contains("pocket_threshold")

    fun setThreshold(ctx: Context, t: Float) {
        threshold = t.coerceIn(MIN_THRESHOLD, 14f)
        Store.prefs(ctx).edit().putFloat("pocket_threshold", threshold).apply()
        AppLog.info("Pocket", "Knock strength set to %.1f".format(java.util.Locale.US, threshold))
    }

    /**
     * Sets the threshold from her own knocks: a bit below her usual knock, so a slightly softer one still counts, but never
     * below Light (taught with gentle taps in the hand, it used to end up so low that a bump in a pocket counted).
     */
    fun calibrate(ctx: Context, strengths: List<Float>): Float {
        val median = strengths.sorted()[strengths.size / 2]
        setThreshold(ctx, median * 0.6f)
        return threshold
    }

    // ---- haptics: strong enough to feel through a pocket, and they play even on silent ----

    /** elapsedRealtime (ms) until which our own vibration is shaking the phone; knocks aren't read meanwhile. */
    @Volatile var mutedUntil = 0L
        private set

    enum class Buzz(val timings: LongArray, val amps: IntArray) {
        Knock(longArrayOf(0, 35), intArrayOf(0, 170)), // each counted knock: a small tick
        Armed(longArrayOf(0, 260, 110, 260, 110, 650), intArrayOf(0, 255, 0, 255, 0, 255)), // three knocks: countdown started
        Tick(longArrayOf(0, 70), intArrayOf(0, 200)), // each second of the countdown
        Sent(longArrayOf(0, 1_000), intArrayOf(0, 255)), // the SOS went out
        Cancelled(longArrayOf(0, 50, 90, 50), intArrayOf(0, 140, 0, 140)),
    }

    fun buzz(ctx: Context, b: Buzz) {
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        if (!v.hasVibrator()) return
        mutedUntil = android.os.SystemClock.elapsedRealtime() + b.timings.sum() + 150
        val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(b.timings, b.amps, -1) else VibrationEffect.createWaveform(b.timings, -1)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            else @Suppress("DEPRECATION") v.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        Store.prefs(ctx).edit().putBoolean(PREF, on).apply()
        val i = Intent(ctx, PocketSosService::class.java)
        if (on) runCatching { ContextCompat.startForegroundService(ctx, i) }.onFailure { AppLog.warn("Pocket", "Couldn't start knock detection", it) }
        else ctx.stopService(i)
        AppLog.info("Pocket", if (on) "Pocket SOS turned on" else "Pocket SOS turned off")
    }

    /** Restarts listening if Android stopped it (called whenever the app opens). */
    fun ensureRunning(ctx: Context) {
        if (enabled(ctx)) runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PocketSosService::class.java)) }
    }

    /** Called on the three knocks: buzz, wake the app, count down, then SOS unless cancelled. */
    fun trigger(ctx: Context) {
        val app = ctx.applicationContext
        if (_countdown.value != null || SosEngine.current.value?.active == true) return
        ignoreScreenUntil = android.os.SystemClock.elapsedRealtime() + (COUNTDOWN_S + 5) * 1_000L
        AppLog.warn("Pocket", "Pocket SOS triggered (${if (powerMode(app)) "power button" else "knocks"}); SOS in $COUNTDOWN_S seconds unless cancelled")
        buzz(app, Buzz.Armed)
        wakeScreen(app)
        job = scope.launch {
            for (s in COUNTDOWN_S downTo 1) {
                _countdown.value = s
                notifyCountdown(app, s)
                if (s < COUNTDOWN_S) buzz(app, Buzz.Tick)
                delay(1_000)
            }
            _countdown.value = null
            clear(app)
            buzz(app, Buzz.Sent)
            SosEngine.start(app, trigger = "pocket", source = "phone")
        }
    }

    fun cancel(ctx: Context) {
        if (_countdown.value == null) return
        job?.cancel()
        _countdown.value = null
        ignoreScreenUntil = android.os.SystemClock.elapsedRealtime() + 1_500 // ready again almost at once after a false alarm
        clear(ctx.applicationContext)
        buzz(ctx.applicationContext, Buzz.Cancelled)
        AppLog.info("Pocket", "Pocket SOS cancelled before it started")
    }

    /** Skip the rest of the countdown. */
    fun sendNow(ctx: Context) {
        if (_countdown.value == null) return
        job?.cancel()
        _countdown.value = null
        clear(ctx.applicationContext)
        buzz(ctx.applicationContext, Buzz.Sent)
        SosEngine.start(ctx.applicationContext, trigger = "pocket", source = "phone")
    }

    /** Android 14+ only shows a full-screen alert (the countdown over the lock screen) once the user allows it. */
    fun canShowOverLockScreen(ctx: Context) =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun askForLockScreenAlerts(ctx: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Lights the screen for the countdown so Cancel is right there. */
    @Suppress("DEPRECATION")
    private fun wakeScreen(ctx: Context) = runCatching {
        ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "s4h:pocket")
            .acquire(8_000)
    }

    private const val ALERT_ID = 131
    private const val ALERT_CH = "pocket_alert"

    private fun notifyCountdown(ctx: Context, s: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALERT_CH) == null) {
            nm.createNotificationChannel(NotificationChannel(ALERT_CH, "Pocket SOS countdown", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 40, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getBroadcast(
            ctx, 41, Intent(ctx, PocketReceiver::class.java).setAction(PocketReceiver.CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, ALERT_CH)
            .setSmallIcon(R.drawable.ic_notif_security4her)
            .setContentTitle("SOS in $s s")
            .setContentText(if (powerMode(ctx)) "Power button pressed 3 times. Cancel if this was a mistake." else "Three knocks detected on your phone. Cancel if this was a mistake.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true) // wakes the phone and opens the countdown over the lock screen
            .addAction(0, "Cancel", cancel)
            .build()
        runCatching { nm.notify(ALERT_ID, n) }
    }

    private fun clear(ctx: Context) = runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_ID) }
}

/** Listens for the knocks in the background. Shows a quiet ongoing notification while on. */
class PocketSosService : Service(), SensorEventListener {
    private var detector: KnockDetector? = null
    private lateinit var power: PowerManager
    private lateinit var keyguard: android.app.KeyguardManager
    private var awake: PowerManager.WakeLock? = null
    private var accel: Sensor? = null
    private var proximity: Sensor? = null
    private var wasInteractive = true
    private var screenOnAt = 0L
    private var screenOffAt = 0L
    private var wokeMidSequence = false
    private var offSamples = 0
    private var rateSent = false
    private var powerMode = false
    private var presses: PowerPressDetector? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onScreenFlip()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(android.app.KeyguardManager::class.java)
        wasInteractive = power.isInteractive
        powerMode = PocketSos.powerMode(this)
        if (powerMode) { startPowerButton(); return }
        detector = KnockDetector(
            threshold = PocketSos.loadThreshold(this),
            onKnock = { n, str ->
                if (n < 3) PocketSos.buzz(this, PocketSos.Buzz.Knock)
                PocketSos.updateTest { it.copy(knocks = maxOf(it.knocks, n), strongest = maxOf(it.strongest, str), reject = null) }
            },
            onSoft = { str -> PocketSos.updateTest { it.copy(soft = it.soft + 1, strongest = maxOf(it.strongest, str)) } },
            onReject = { r -> PocketSos.updateTest { it.copy(reject = r) } },
            onTriple = {
                if (PocketSos.testing()) {
                    PocketSos.updateTest { it.copy(passed = true, knocks = 3) }
                    PocketSos.buzz(this, PocketSos.Buzz.Armed)
                    AppLog.ok("Pocket", "Screen-off knock test passed")
                } else PocketSos.trigger(this)
            },
        )
        // With the screen off many phones sleep the processor and stop the motion sensor; keep it awake to listen
        awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "s4h:pocket-listen").apply { setReferenceCounted(false); acquire() }
        val sm = getSystemService(SensorManager::class.java)
        // The wake-up version keeps arriving on phones that pause ordinary sensors with the screen off
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) {
            AppLog.error("Pocket", "This phone has no motion sensor, so Pocket SOS can't work")
            stopSelf(); return
        }
        accel = acc
        sm.registerListener(this, acc, 5_000, 0) // ask for 200 Hz, unbatched; phones give what they can, knocks last 20-40 ms
        // Covered (pocket, bag) or not (on a table): tells the detector when a tap on a hard surface is likely
        proximity = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximity?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    /**
     * Power-button mode: no motion sensor and no always-on wake lock. Screen flips arrive as broadcasts; a short wake
     * lock after each one makes sure the count is checked even if the phone wants to sleep straight away.
     */
    private fun startPowerButton() {
        presses = PowerPressDetector {
            if (PocketSos.testing()) {
                PocketSos.updateTest { it.copy(passed = true, knocks = 3) }
                PocketSos.buzz(this, PocketSos.Buzz.Armed)
                AppLog.ok("Pocket", "Power-button test passed")
            } else PocketSos.trigger(this)
        }
        val f = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun onScreenFlip() {
        val now = android.os.SystemClock.elapsedRealtime()
        val d = presses ?: return
        if (now < PocketSos.ignoreScreenUntil || PocketSos.countdown.value != null || SosEngine.current.value?.active == true) { d.reset(); return }
        // During a call the screen goes dark against her ear and lights up again: that's the proximity sensor, not her
        val mode = getSystemService(android.media.AudioManager::class.java).mode
        if (mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION || mode == android.media.AudioManager.MODE_RINGTONE) { d.reset(); return }
        d.press(now)
        PocketSos.updateTest { it.copy(screenWentOff = true, knocks = maxOf(it.knocks, d.count)) }
        if (d.count in 1..2) PocketSos.buzz(this, PocketSos.Buzz.Knock)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ d.tick(android.os.SystemClock.elapsedRealtime()) }, PowerPressDetector.MAX_GAP + 50)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PocketSos.enabled(this) && !PocketSos.testing()) { stopSelf(); return START_NOT_STICKY }
        val n = build(this, testing = !PocketSos.enabled(this))
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        } catch (e: Exception) {
            AppLog.warn("Pocket", "Couldn't keep knock detection running", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        val d = detector ?: return
        if (e.sensor.type == Sensor.TYPE_PROXIMITY) {
            d.covered = e.values[0] < minOf(5f, e.sensor.maximumRange)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val interactive = power.isInteractive
        if (interactive != wasInteractive) { wasInteractive = interactive; screenChanged(interactive, now, d) }
        if (!interactive) offSamples++
        // Screen on means she's using the phone: taps on glass must never count. Except on phones where knocking on the
        // glass switches the screen on (tap to wake): a sequence that woke it keeps going briefly while it's still locked.
        val wokeByKnocks = interactive && wokeMidSequence && now - screenOnAt < 1_500 && keyguard.isKeyguardLocked
        if ((interactive && !wokeByKnocks) || PocketSos.practicing) { d.reset(); return }
        if (now < PocketSos.mutedUntil) { d.cancelJolt(); return } // our own buzz
        d.threshold = PocketSos.threshold
        d.feed(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000)
    }

    private fun screenChanged(on: Boolean, now: Long, d: KnockDetector) {
        if (!on) { screenOffAt = now; offSamples = 0; PocketSos.updateTest { it.copy(screenWentOff = true) }; return }
        screenOnAt = now
        wokeMidSequence = d.inProgress
        val offMs = now - screenOffAt
        if (screenOffAt == 0L || offMs < 3_000) return
        PocketSos.updateTest { it.copy(offMs = it.offMs + offMs, samples = it.samples + offSamples) }
        val hz = (offSamples * 1000L / offMs).toInt()
        if (hz == 0 && offMs > 10_000) AppLog.warn("Pocket", "No motion readings while the screen was off; this phone pauses the sensor")
        if (!rateSent) { // once per run: what this phone really delivers with the screen off
            rateSent = true
            `in`.security4her.app.beta.Beta.record(
                `in`.security4her.app.beta.BetaCategory.PERFORMANCE, "info", "pocket_screen_off_rate", null,
                mapOf("hz" to hz, "wakeup_sensor" to (accel?.isWakeUpSensor == true), "proximity" to (proximity != null)),
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        if (powerMode) { runCatching { unregisterReceiver(screenReceiver) }; handler.removeCallbacksAndMessages(null) }
        getSystemService(SensorManager::class.java).unregisterListener(this)
        runCatching { awake?.release() }
        super.onDestroy()
    }

    companion object {
        private const val ID = 130
        private const val CH = "pocket"

        private fun build(ctx: Context, testing: Boolean = false): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Pocket SOS", NotificationManager.IMPORTANCE_MIN))
            val off = PendingIntent.getBroadcast(ctx, 42, Intent(ctx, PocketReceiver::class.java).setAction(PocketReceiver.TURN_OFF), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(ctx, 43, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_notif_security4her)
                .setContentTitle(if (testing) "Testing knocks" else "Pocket SOS is on")
                .setContentText(
                    if (PocketSos.powerMode(ctx)) { if (testing) "Press the power button 3 times. Nothing is sent." else "Press the power button 3 times to start an SOS." }
                    else if (testing) "Turn the screen off and knock 3 times. Nothing is sent." else "Knock 3 times on your phone to start an SOS.",
                )
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(open)
                .addAction(0, "Turn off", off)
                .build()
        }
    }
}

/** Cancel / Turn off from our own notifications (not exported). */
class PocketReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            CANCEL -> PocketSos.cancel(ctx)
            TURN_OFF -> PocketSos.setEnabled(ctx, false)
        }
    }

    companion object {
        const val CANCEL = "in.security4her.app.POCKET_CANCEL"
        const val TURN_OFF = "in.security4her.app.POCKET_OFF"
    }
}

/** Turns knock detection back on after a reboot or an app update. */
class PocketBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) `in`.security4her.app.SmsUnlock.afterReinstall(ctx)
        if (PocketSos.enabled(ctx)) runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PocketSosService::class.java)) }
    }
}

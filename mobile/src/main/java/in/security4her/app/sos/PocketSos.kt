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

    /** The latest jump, for a live meter. */
    @Volatile var level = 0f
        private set

    /** Drop a jolt in progress (e.g. while the phone itself is vibrating). */
    fun cancelJolt() { phase = Phase.Idle }

    fun reset() { knocks.clear(); fireAt = 0L; phase = Phase.Idle }

    /** [t] in milliseconds. */
    fun feed(x: Float, y: Float, z: Float, t: Long) {
        if (!primed) { prev[0] = x; prev[1] = y; prev[2] = z; primed = true; lastT = t; return }
        val dt = (t - lastT).coerceIn(1, 100).toFloat()
        lastT = t
        val dx = x - prev[0]; val dy = y - prev[1]; val dz = z - prev[2]
        prev[0] = x; prev[1] = y; prev[2] = z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        level = d
        recent += (d - recent) * dt / (50f + dt)

        if (fireAt != 0L && t >= fireAt) { fireAt = 0L; knocks.clear(); onTriple(); return }
        if (fireAt == 0L && knocks.isNotEmpty() && t - knocks.last() > MAX_GAP) { knocks.clear(); onReject(Reject.TooSlow) }

        when (phase) {
            Phase.Idle -> {
                activity += (d - activity) * dt / (500f + dt)
                val b = maxOf(threshold, activity * 4f)
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

    // ---- how hard a knock must be (m/s^2 of jolt) ----
    enum class Sensitivity(val label: String, val threshold: Float) { High("Light", 2.8f), Normal("Normal", 4.5f), Low("Firm", 7.5f) }

    @Volatile var threshold = KnockDetector.DEFAULT_THRESHOLD
        private set

    fun loadThreshold(ctx: Context): Float {
        threshold = Store.prefs(ctx).getFloat("pocket_threshold", KnockDetector.DEFAULT_THRESHOLD)
        return threshold
    }

    /** True once she has tuned the strength to her own knocks (or picked one). */
    fun calibrated(ctx: Context) = Store.prefs(ctx).contains("pocket_threshold")

    fun setThreshold(ctx: Context, t: Float) {
        threshold = t.coerceIn(1.8f, 14f)
        Store.prefs(ctx).edit().putFloat("pocket_threshold", threshold).apply()
        AppLog.info("Pocket", "Knock strength set to %.1f".format(java.util.Locale.US, threshold))
    }

    /** Sets the threshold from her own knocks: a bit below her usual knock, so a slightly softer one still counts. */
    fun calibrate(ctx: Context, strengths: List<Float>): Float {
        val median = strengths.sorted()[strengths.size / 2]
        setThreshold(ctx, median * 0.55f)
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
        AppLog.warn("Pocket", "Three knocks detected; SOS in $COUNTDOWN_S seconds unless cancelled")
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
            .setContentText("Three knocks detected on your phone. Cancel if this was a mistake.")
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
    private var awake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        power = getSystemService(PowerManager::class.java)
        detector = KnockDetector(
            threshold = PocketSos.loadThreshold(this),
            onKnock = { n, _ -> if (n < 3) PocketSos.buzz(this, PocketSos.Buzz.Knock) },
            onTriple = { PocketSos.trigger(this) },
        )
        // With the screen off many phones sleep the processor and stop the motion sensor; keep it awake to listen
        awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "s4h:pocket-listen").apply { setReferenceCounted(false); acquire() }
        val sm = getSystemService(SensorManager::class.java)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) {
            AppLog.error("Pocket", "This phone has no motion sensor, so Pocket SOS can't work")
            stopSelf(); return
        }
        sm.registerListener(this, acc, 5_000) // ask for 200 Hz; phones give what they can, knocks last 20-40 ms
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PocketSos.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        val n = build(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        } catch (e: Exception) {
            AppLog.warn("Pocket", "Couldn't keep knock detection running", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        // Screen on means she's using the phone: taps on glass must never count
        val d = detector ?: return
        if (power.isInteractive || PocketSos.practicing) { d.reset(); return }
        if (android.os.SystemClock.elapsedRealtime() < PocketSos.mutedUntil) { d.cancelJolt(); return } // our own buzz
        d.threshold = PocketSos.threshold
        d.feed(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        getSystemService(SensorManager::class.java).unregisterListener(this)
        runCatching { awake?.release() }
        super.onDestroy()
    }

    companion object {
        private const val ID = 130
        private const val CH = "pocket"

        private fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Pocket SOS", NotificationManager.IMPORTANCE_MIN))
            val off = PendingIntent.getBroadcast(ctx, 42, Intent(ctx, PocketReceiver::class.java).setAction(PocketReceiver.TURN_OFF), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(ctx, 43, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_notif_security4her)
                .setContentTitle("Pocket SOS is on")
                .setContentText("Knock 3 times on your phone to start an SOS.")
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
        if (PocketSos.enabled(ctx)) runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PocketSosService::class.java)) }
    }
}

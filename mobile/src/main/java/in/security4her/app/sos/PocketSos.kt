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
 * Finds three short, sharp knocks in accelerometer data.
 * A knock is a brief spike (under ~120 ms) above [high]; walking and running make longer, rhythmic bumps,
 * so a knock must follow a still moment, the three must be 120–700 ms apart, and nothing may follow for 450 ms.
 */
class KnockDetector(
    private val high: Float = 3.0f,
    private val low: Float = 1.2f,
    private val onKnock: (count: Int) -> Unit = {},
    private val onTriple: () -> Unit,
) {
    private val g = FloatArray(3)
    private var primed = false
    private var inSpike = false
    private var spikeStart = 0L
    private var lastSpikeEnd = 0L
    private var lastBusy = 0L // last long movement (walking, a bump)
    private val knocks = ArrayList<Long>(4)
    private var fireAt = 0L

    fun reset() { knocks.clear(); fireAt = 0L; inSpike = false }

    /** [t] in milliseconds. */
    fun feed(x: Float, y: Float, z: Float, t: Long) {
        if (!primed) { g[0] = x; g[1] = y; g[2] = z; primed = true; return }
        // Slow low-pass tracks gravity; what's left is the jolt
        g[0] = g[0] * 0.95f + x * 0.05f; g[1] = g[1] * 0.95f + y * 0.05f; g[2] = g[2] * 0.95f + z * 0.05f
        val dx = x - g[0]; val dy = y - g[1]; val dz = z - g[2]
        val m = sqrt(dx * dx + dy * dy + dz * dz)

        if (fireAt != 0L && t >= fireAt) { fireAt = 0L; knocks.clear(); onTriple(); return }

        if (!inSpike && m > high && t - lastSpikeEnd > 80) { inSpike = true; spikeStart = t }
        else if (inSpike && m < low) {
            inSpike = false
            lastSpikeEnd = t
            if (t - spikeStart <= 120) knock(spikeStart) else { lastBusy = t; reset() }
        } else if (inSpike && t - spikeStart > 250) { inSpike = false; lastSpikeEnd = t; lastBusy = t; reset() }
    }

    private fun knock(t: Long) {
        if (knocks.isEmpty() && t - lastBusy < 500) return // moving about: not a deliberate knock
        val prev = knocks.lastOrNull()
        if (prev != null && t - prev > 700) knocks.clear()
        if (prev != null && t - prev < 120) return
        if (fireAt != 0L) { reset(); return } // a fourth knock: rhythm, not a signal
        knocks += t
        onKnock(knocks.size)
        if (knocks.size == 3) fireAt = t + 450
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

    fun setEnabled(ctx: Context, on: Boolean) {
        Store.prefs(ctx).edit().putBoolean(PREF, on).apply()
        val i = Intent(ctx, PocketSosService::class.java)
        if (on) runCatching { ContextCompat.startForegroundService(ctx, i) }.onFailure { AppLog.warn("Pocket", "Couldn't start knock detection", it) }
        else ctx.stopService(i)
        AppLog.info("Pocket", if (on) "Pocket SOS turned on" else "Pocket SOS turned off")
    }

    /** Called on the three knocks: buzz, wake the app, count down, then SOS unless cancelled. */
    fun trigger(ctx: Context) {
        val app = ctx.applicationContext
        if (_countdown.value != null || SosEngine.current.value?.active == true) return
        AppLog.warn("Pocket", "Three knocks detected; SOS in $COUNTDOWN_S seconds unless cancelled")
        buzz(app, longArrayOf(0, 120, 90, 120))
        job = scope.launch {
            for (s in COUNTDOWN_S downTo 1) {
                _countdown.value = s
                notifyCountdown(app, s)
                if (s < COUNTDOWN_S) buzz(app, longArrayOf(0, 60))
                delay(1_000)
            }
            _countdown.value = null
            clear(app)
            SosEngine.start(app, trigger = "pocket", source = "phone")
        }
    }

    fun cancel(ctx: Context) {
        if (_countdown.value == null) return
        job?.cancel()
        _countdown.value = null
        clear(ctx.applicationContext)
        AppLog.info("Pocket", "Pocket SOS cancelled before it started")
    }

    /** Skip the rest of the countdown. */
    fun sendNow(ctx: Context) {
        if (_countdown.value == null) return
        job?.cancel()
        _countdown.value = null
        clear(ctx.applicationContext)
        SosEngine.start(ctx.applicationContext, trigger = "pocket", source = "phone")
    }

    fun buzz(ctx: Context, pattern: LongArray) {
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        power = getSystemService(PowerManager::class.java)
        detector = KnockDetector(onTriple = { PocketSos.trigger(this) })
        val sm = getSystemService(SensorManager::class.java)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) {
            AppLog.error("Pocket", "This phone has no motion sensor, so Pocket SOS can't work")
            stopSelf(); return
        }
        sm.registerListener(this, acc, 10_000) // 100 Hz: enough to catch a knock's short spike
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
        if (power.isInteractive || PocketSos.practicing) { detector?.reset(); return }
        detector?.feed(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        getSystemService(SensorManager::class.java).unregisterListener(this)
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

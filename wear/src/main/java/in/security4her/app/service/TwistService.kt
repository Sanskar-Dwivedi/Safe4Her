package `in`.security4her.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import `in`.security4her.app.MainActivity
import `in`.security4her.app.R
import `in`.security4her.app.WatchApp
import `in`.security4her.app.data.WatchStore
import `in`.security4her.app.sos.FakeCallController
import `in`.security4her.app.sos.SosController
import `in`.security4her.app.sos.TwistRuntime
import `in`.security4her.app.sos.TwistStrength
import `in`.security4her.app.sos.WristTwistDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** User-armed motion monitoring, independent of location and microphone permissions. */
class TwistService : LifecycleService() {
    private data class State(val enabled: Boolean, val strength: TwistStrength, val sos: Boolean, val practice: Boolean)
    private var state: State? = null
    private var detector: WristTwistDetector? = null
    private var detectorStrength: TwistStrength? = null
    private var startedAt = 0L
    private var foreground = false
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        val store = WatchStore.get(this)
        lifecycleScope.launch {
            combine(store.fakeCallTwist, store.twistStrength, store.incident, SosController.countdown, TwistRuntime.practice) {
                    enabled, strength, incident, countdown, practice ->
                State(enabled, strength, incident?.ended == false || countdown != null, practice)
            }.distinctUntilChanged().collect {
                state = it
                refresh()
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                // Also recovers readiness after cooldown and detects an OEM-paused sensor stream.
                refresh()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
            foreground = true
            refresh()
            START_STICKY
        } catch (e: Exception) {
            Log.w(TAG, "Gesture foreground service refused", e)
            TwistRuntime.report(TwistRuntime.Status.BLOCKED)
            val app = applicationContext
            CoroutineScope(Dispatchers.Default).launch { Rearm.askToResume(app) }
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun refresh() {
        val s = state ?: return
        if (!s.enabled) {
            stopDetector()
            stopSelf()
            return
        }
        if (!foreground) return
        val paused = when {
            s.sos -> TwistRuntime.Status.SOS
            s.practice -> TwistRuntime.Status.PRACTICE
            !power.isInteractive -> TwistRuntime.Status.ASLEEP
            TwistRuntime.coolingDown() -> TwistRuntime.Status.COOLDOWN
            else -> null
        }
        if (paused != null) {
            stopDetector()
            report(paused)
            return
        }
        if (detectorStrength != s.strength) stopDetector()
        if (detector == null) {
            val next = WristTwistDetector(this, s.strength) {
                // Check synchronously as well as observing flows; practice must never send a call.
                if (state?.sos == false && SosController.countdown.value == null &&
                    power.isInteractive && TwistRuntime.claimTrigger()) {
                    FakeCallController.request(this)
                    refresh()
                }
            }
            if (!next.start()) {
                report(TwistRuntime.Status.UNAVAILABLE)
                return
            }
            detector = next
            detectorStrength = s.strength
            startedAt = SystemClock.elapsedRealtime()
        }
        val last = detector?.lastSampleAt ?: 0L
        report(when {
            last == 0L && SystemClock.elapsedRealtime() - startedAt < 2_000 -> TwistRuntime.Status.STARTING
            SystemClock.elapsedRealtime() - last > 2_000 -> TwistRuntime.Status.STALLED
            else -> TwistRuntime.Status.READY
        })
    }

    private fun stopDetector() {
        detector?.stop()
        detector = null
        detectorStrength = null
    }

    private fun report(value: TwistRuntime.Status) {
        if (TwistRuntime.status.value == value) return
        TwistRuntime.report(value)
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 44, Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_STATUS_ONLY, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, WatchApp.CHANNEL_GESTURES)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Fake call wrist twist")
            .setContentText(TwistRuntime.status.value.label)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    override fun onDestroy() {
        stopDetector()
        unregisterReceiver(screenReceiver)
        foreground = false
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (TwistRuntime.status.value != TwistRuntime.Status.BLOCKED) TwistRuntime.report(TwistRuntime.Status.STOPPED)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "S4hTwist"
        private const val NOTIFICATION_ID = 4245
        fun ensure(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TwistService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Gesture service start refused", e)
            TwistRuntime.report(TwistRuntime.Status.BLOCKED)
            false
        }
    }
}

package `in`.security4her.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import `in`.security4her.app.MainActivity
import `in`.security4her.app.R
import `in`.security4her.app.WatchApp
import `in`.security4her.app.data.ActiveIncident
import `in`.security4her.app.data.WatchStore
import `in`.security4her.app.sos.Locator
import `in`.security4her.app.sos.ShakeDetector
import `in`.security4her.app.sos.SosController
import `in`.security4her.app.sos.WatchRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (type location) that runs while Safety Mode is on or an incident is live:
 * - Safety Mode: shake detection → the same 5 s countdown as the gesture launch.
 * - Live incident: location heartbeat every 30 s + re-delivery to phone/server until acknowledged.
 * Stops itself when neither applies.
 */
class SafetyService : LifecycleService() {

    private var shake: ShakeDetector? = null
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val store = WatchStore.get(this)
        lifecycleScope.launch {
            combine(store.safetyMode, store.incident, SosController.countdown) { safety, incident, countdown ->
                Triple(safety, incident?.takeIf { !it.ended }, countdown)
            }.distinctUntilChanged().collect { (safety, incident, countdown) ->
                setShake(safety)
                setHeartbeat(incident != null)
                // Evidence: record from the start of the SOS until "I'm safe".
                if (incident != null && !WatchRecorder.recording) {
                    promoteToForeground() // re-claim the microphone type if we were started before it was allowed
                    WatchRecorder.start(this@SafetyService, incident.incidentId)
                } else if (incident == null && WatchRecorder.recording) {
                    WatchRecorder.stop(this@SafetyService)
                }
                if (!safety && incident == null && countdown == null) {
                    stopSelf()
                } else {
                    notify(buildNotification(safety, incident, countdown))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // State (Safety Mode, incident) lives in WatchStore and is written *before* the service starts;
        // the service only observes it, so there is no start/stop race.
        if (intent?.action == ACTION_CANCEL_COUNTDOWN) SosController.cancelCountdown()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WatchRecorder.stop(this)
        shake?.stop()
        shake = null
        running = false
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean = try {
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val withMic = if (WatchRecorder.hasPermission(this)) loc or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else loc
        try {
            // Holding the microphone type now (while the app is on screen) lets a later shake-triggered SOS record.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(false, null, null), withMic)
        } catch (e: Exception) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(false, null, null), loc)
        }
        true
    } catch (e: Exception) {
        // Missing location permission or a background-start restriction.
        Log.e(TAG, "cannot start foreground service", e)
        false
    }

    private fun setShake(on: Boolean) {
        if (on && shake == null) {
            shake = ShakeDetector(this) { SosController.beginCountdown(this, "shake") }.also {
                if (!it.start()) Log.w(TAG, "no accelerometer")
            }
        } else if (!on && shake != null) {
            shake?.stop()
            shake = null
        }
    }

    private fun setHeartbeat(on: Boolean) {
        if (on && heartbeatJob?.isActive != true) {
            heartbeatJob = lifecycleScope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    runCatching { SosController.heartbeat(this@SafetyService) }
                        .onFailure { Log.w(TAG, "heartbeat failed", it) }
                    runCatching { WatchRecorder.flush(this@SafetyService) }
                }
            }
        } else if (!on) {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        safety: Boolean,
        incident: ActiveIncident?,
        countdown: SosController.Countdown?,
    ): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_STATUS_ONLY, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            countdown != null -> "SOS in ${countdown.secondsLeft} s — tap Cancel if this is a mistake"
            incident != null && incident.delivered -> "SOS active — alert delivered"
            incident != null -> "SOS active — NOT SENT yet, retrying"
            safety -> "Safety Mode on — shake 3 times for SOS"
            else -> "Security4Her"
        }
        val builder = NotificationCompat.Builder(this, WatchApp.CHANNEL_SAFETY)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(if (incident != null || countdown != null) "SOS" else "Security4Her")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (countdown != null) {
            val cancel = PendingIntent.getService(
                this, 1,
                Intent(this, SafetyService::class.java).setAction(ACTION_CANCEL_COUNTDOWN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(R.drawable.ic_shield, "Cancel", cancel)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "S4hSafetyService"
        private const val NOTIFICATION_ID = 4242
        private const val HEARTBEAT_MS = 30_000L
        const val ACTION_CANCEL_COUNTDOWN = "in.security4her.app.CANCEL_COUNTDOWN"
        const val ACTION_ENSURE = "in.security4her.app.ENSURE"

        @Volatile var running = false
            private set

        /** Call from a foreground context (activity visible), where starting a location FGS is allowed. */
        fun send(context: Context, action: String) {
            if (!Locator.hasPermission(context)) return
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, SafetyService::class.java).setAction(action),
                )
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
            }
        }

        /** Starts the service for an incident if it isn't running (already running: it observes the store). */
        fun ensureRunning(context: Context) {
            if (!running) send(context, ACTION_ENSURE)
        }
    }
}

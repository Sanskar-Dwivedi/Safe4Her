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
import `in`.security4her.app.voice.VoiceListener
import `in`.security4her.app.voice.VoiceSos
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (type location + microphone) that runs while Safety Mode or Voice SOS is on, or an
 * incident is live:
 * - Safety Mode: shake detection → the same 5 s countdown as the gesture launch.
 * - Voice SOS: listens for "HELP HELP HELP" → the same countdown. Pauses during a countdown or SOS (the SOS
 *   recorder needs the microphone) and while the practice screen is open.
 * - Live incident: location heartbeat every 30 s + re-delivery to phone/server until acknowledged.
 * Stops itself when none applies.
 */
class SafetyService : LifecycleService() {

    private var shake: ShakeDetector? = null
    private var voice: VoiceListener? = null
    private var lastState: State? = null

    private data class State(
        val safety: Boolean, val voice: Boolean, val practicing: Boolean,
        val incident: ActiveIncident?, val countdown: SosController.Countdown?,
    )
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val store = WatchStore.get(this)
        lifecycleScope.launch {
            combine(store.safetyMode, store.voiceSos, VoiceSos.practicing, store.incident, SosController.countdown) {
                    safety, voiceOn, practicing, incident, countdown ->
                State(safety, voiceOn, practicing, incident?.takeIf { !it.ended }, countdown)
            }.distinctUntilChanged().collect { state ->
                lastState = state
                val (safety, voiceOn, practicing, incident, countdown) = state
                setShake(safety)
                // Voice first: it must let go of the microphone before the SOS recorder starts.
                setVoice(voiceOn && !practicing && incident == null && countdown == null, voiceOn)
                setHeartbeat(incident != null)
                // Evidence: record from the start of the SOS until "I'm safe".
                if (incident != null && !WatchRecorder.recording) {
                    promoteToForeground() // re-claim the microphone type if we were started before it was allowed
                    WatchRecorder.start(this@SafetyService, incident.incidentId)
                } else if (incident == null && WatchRecorder.recording) {
                    WatchRecorder.stop(this@SafetyService)
                }
                if (!safety && !voiceOn && incident == null && countdown == null) {
                    stopSelf()
                } else {
                    notify(buildNotification(safety, incident, countdown, voiceOn))
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
        if (intent?.action == ACTION_ENSURE) {
            // A listener that gave up (e.g. the microphone wasn't allowed yet) gets another go.
            if (voice?.isRunning == false) { voice = null }
            lastState?.let { s -> setVoice(s.voice && !s.practicing && s.incident == null && s.countdown == null, s.voice) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WatchRecorder.stop(this)
        voice?.stop()
        voice = null
        shake?.stop()
        shake = null
        running = false
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean = try {
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        // Microphone type: needed by Voice SOS (listening) and the SOS recorder. It can only be claimed while the app is visible.
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

    private fun setVoice(listen: Boolean, enabled: Boolean) {
        if (listen && voice == null) {
            voice = VoiceListener(this) {
                if (VoiceSos.claimTrigger()) SosController.beginCountdown(this, "voice")
            }.also { it.start() }
        } else if (!listen && voice != null) {
            voice?.stop()
            voice = null
            VoiceSos.report(if (enabled) VoiceSos.Status.Paused else VoiceSos.Status.Off)
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
        voiceOn: Boolean = false,
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
            safety && voiceOn -> "Shake 3 times, or say HELP HELP HELP, for SOS"
            safety -> "Safety Mode on — shake 3 times for SOS"
            voiceOn -> "Voice SOS on — say HELP HELP HELP"
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

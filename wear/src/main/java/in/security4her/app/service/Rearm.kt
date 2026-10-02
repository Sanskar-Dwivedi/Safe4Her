package `in`.security4her.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import `in`.security4her.app.MainActivity
import `in`.security4her.app.R
import `in`.security4her.app.WatchApp
import `in`.security4her.app.data.WatchStore
import `in`.security4her.app.sos.WatchUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings protection back after the watch restarts or the app updates. Safety Mode, Voice SOS, and fake-call twist stay switched on in
 * WatchStore, but Android stops the service; this restarts it. Android doesn't let an app open the microphone from
 * the background, so when Voice SOS can't come back by itself the watch shows one notification: tap to resume.
 */
class Rearm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WatchUpdater.schedule(app)
                val store = WatchStore.get(app)
                val safetyArmed = store.safetyModeNow() || store.voiceSos.first() || store.incidentNow()?.ended == false
                val safetyStarted = !safetyArmed || SafetyService.send(app, SafetyService.ACTION_ENSURE)
                val twistStarted = !store.fakeCallTwistNow() || TwistService.ensure(app)
                if (!safetyStarted || !twistStarted) askToResume(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "S4hRearm"
        private const val NOTIFY_ID = 4344

        suspend fun armed(ctx: Context): Boolean {
            val store = WatchStore.get(ctx)
            return store.safetyModeNow() || store.voiceSos.first() || store.fakeCallTwistNow() || store.incidentNow()?.ended == false
        }

        /** One tap opens the app, which re-arms everything while it's on screen. */
        suspend fun askToResume(ctx: Context) {
            val store = WatchStore.get(ctx)
            val what = when {
                store.fakeCallTwistNow() && (store.voiceSos.first() || store.safetyModeNow()) -> "Watch protection is"
                store.fakeCallTwistNow() -> "Fake call wrist twist is"
                store.voiceSos.first() && store.safetyModeNow() -> "Voice SOS and Safety Mode are"
                store.voiceSos.first() -> "Voice SOS is"
                else -> "Safety Mode is"
            }
            Log.i(TAG, "asking to resume: $what paused")
            `in`.security4her.app.beta.WatchBeta.event("usage", "warning", "protection_paused_after_restart", null)
            val open = PendingIntent.getActivity(
                ctx, 3,
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_STATUS_ONLY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, WatchApp.CHANNEL_SAFETY)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("Tap to resume")
                .setContentText("$what paused. Tap to turn protection back on.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n) }
        }

        fun clear(ctx: Context) {
            runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID) }
        }
    }
}

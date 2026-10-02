package `in`.security4her.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import `in`.security4her.app.sos.WatchUpdater
import `in`.security4her.app.voice.VoiceSos

class WatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        `in`.security4her.app.beta.WatchBeta.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SAFETY, getString(R.string.channel_safety), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_GESTURES, "Fake call wrist twist", NotificationManager.IMPORTANCE_LOW))
        WatchUpdater.schedule(this)
        // Unpack the speech model early (once, ~40 MB) so turning Voice SOS on is instant.
        Thread { runCatching { VoiceSos.prepare(this) } }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    companion object {
        const val CHANNEL_SAFETY = "s4h_safety"
        const val CHANNEL_UPDATES = "s4h_updates"
        const val CHANNEL_GESTURES = "s4h_gestures"
    }
}

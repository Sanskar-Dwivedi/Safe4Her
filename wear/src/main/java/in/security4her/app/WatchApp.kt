package `in`.security4her.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class WatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_SAFETY,
            getString(R.string.channel_safety),
            NotificationManager.IMPORTANCE_HIGH,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_SAFETY = "s4h_safety"
    }
}

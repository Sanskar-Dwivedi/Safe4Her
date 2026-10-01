package `in`.security4her.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import `in`.security4her.app.core.FakeCallRequest
import `in`.security4her.app.core.Store
import java.util.UUID

/** Shows the existing fake-call screen already ringing when opened from a watch request. */
class FakeCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java).cancel(FakeCallLauncher.NOTIFICATION_ID)
        Palettes.load(this)
        setContent { S4hTheme { FakeCallScreen(onClose = { finish() }, startImmediately = true) } }
    }
}

/** A background watch message posts a call-like notification; the phone opens the call when Android permits it. */
object FakeCallLauncher {
    internal const val NOTIFICATION_ID = 4243
    private const val CHANNEL = "fake_call_from_watch"
    private var lastRequestId: String? = null
    private var lastLaunchAt = 0L

    @Synchronized
    fun request(context: Context, request: FakeCallRequest): Boolean {
        val now = System.currentTimeMillis()
        if (runCatching { UUID.fromString(request.requestId) }.isFailure ||
            request.createdAt > now + 30_000 || now - request.createdAt > 30_000) return false
        if (request.requestId == lastRequestId || now - lastLaunchAt < 10_000) return true

        val app = context.applicationContext
        val open = Intent(app, FakeCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val accepted = runCatching {
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                app.startActivity(open)
            } else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
                val manager = app.getSystemService(NotificationManager::class.java)
                if (!manager.areNotificationsEnabled()) return false
                if (manager.getNotificationChannel(CHANNEL) == null) {
                    val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Fake call from watch", NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(sound, attributes)
                        enableVibration(true)
                    })
                }
                if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
                val caller = Store.prefs(app).getString("fake_caller", "Mom")?.trim().orEmpty().ifBlank { "Mom" }.take(24)
                val pending = PendingIntent.getActivity(app, 43, open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val notification = NotificationCompat.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif_security4her)
                    .setContentTitle("Incoming call from $caller")
                    .setContentText("Tap to answer")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setTimeoutAfter(30_000)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                    .apply {
                        if (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()) setFullScreenIntent(pending, true)
                    }
                    .build()
                manager.notify(NOTIFICATION_ID, notification)
            }
        }.isSuccess
        if (accepted) {
            lastRequestId = request.requestId
            lastLaunchAt = now
        }
        return accepted
    }
}

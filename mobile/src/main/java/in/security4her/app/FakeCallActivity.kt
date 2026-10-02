package `in`.security4her.app

import android.Manifest
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import `in`.security4her.app.core.FakeCallRequest
import `in`.security4her.app.core.Store
import java.util.UUID

/** Reuses the existing ringing screen, including for a new request delivered to a running activity. */
class FakeCallActivity : ComponentActivity() {
    private var requestGeneration by mutableIntStateOf(0)
    private var shownRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shownRequestId = intent.getStringExtra(FakeCallLauncher.EXTRA_REQUEST_ID)
        clearNotification()
        Palettes.load(this)
        setContent {
            S4hTheme {
                key(requestGeneration) { FakeCallScreen(onClose = { finish() }, startImmediately = true) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        clearNotification()
        val requestId = intent.getStringExtra(FakeCallLauncher.EXTRA_REQUEST_ID)
        if (requestId != shownRequestId) {
            shownRequestId = requestId
            requestGeneration++
        }
    }

    private fun clearNotification() {
        getSystemService(NotificationManager::class.java).cancel(FakeCallLauncher.NOTIFICATION_ID)
    }
}

/** User-visible, optional special-access setup. Never opened automatically by a watch command. */
@Composable
internal fun WatchFakeCallAccess() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }
    Column {
        Text("Fake calls from your watch", style = MaterialTheme.typography.titleSmall)
        Text(
            if (allowed) "Appear on top is allowed. Watch requests can open the call while you use another app."
            else "To open the call while you use another app, allow Appear on top for Security4Her. Without it, an unlocked phone shows a call notification.",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(context, "Open Settings → Apps → Special access → Appear on top → Security4Her.", Toast.LENGTH_LONG).show()
            }
        }) { Text(if (allowed) "Manage appear on top" else "Allow appear on top") }
    }
}

/**
 * Unlocked phones normally show a heads-up notification for a full-screen intent.
 * User-granted SYSTEM_ALERT_WINDOW is a documented background activity launch exception.
 * Keep the notification as a fallback: startActivity can be silently blocked by an OEM.
 */
object FakeCallLauncher {
    internal const val NOTIFICATION_ID = 4243
    internal const val EXTRA_REQUEST_ID = "in.security4her.app.FAKE_CALL_REQUEST_ID"
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
            .putExtra(EXTRA_REQUEST_ID, request.requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        // Post first so the activity can cancel it when it appears. No extra tap is needed with access.
        val notified = if (!visible) runCatching { postNotification(app, open) }.getOrDefault(false) else false
        // Preserve the existing locked-screen full-screen-intent path; direct launch fills its unlocked-screen gap.
        val unlockedAndAwake = app.getSystemService(PowerManager::class.java).isInteractive &&
            !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val launched = if (visible || (unlockedAndAwake && Settings.canDrawOverlays(app))) {
            runCatching { app.startActivity(open) }.isSuccess
        } else false
        val accepted = notified || launched
        if (accepted) {
            lastRequestId = request.requestId
            lastLaunchAt = now
        }
        return accepted
    }

    private fun postNotification(app: Context, open: Intent): Boolean {
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
            .setContentTitle("Incoming call from " + caller)
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
        return true
    }
}

package `in`.security4her.app.sos

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import `in`.security4her.app.BuildConfig
import `in`.security4her.app.R
import `in`.security4her.app.WatchApp
import `in`.security4her.app.data.WatchStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Automatic updates for the watch. A background job reads the "watch" entry of the site's version.json every few
 * hours, downloads a newer APK over the watch's own connection (Wi-Fi or the phone's Bluetooth proxy), checks its
 * SHA-256 and installs it through PackageInstaller: silently when the app is its own installer (the phone installer
 * sets that up), otherwise after one tap on a notification. Data and the phone link are kept.
 *
 * It never updates during a countdown or an SOS, and while Voice SOS or Safety Mode is armed it waits until the
 * watch is charging, because installing restarts the app. Afterwards [`in`.security4her.app.service.Rearm] brings
 * protection back.
 */
object WatchUpdater {
    private const val TAG = "S4hUpdate"
    private const val WORK = "s4h-update"
    private const val NOTIFY_ID = 4343

    data class Release(val code: Int, val name: String, val url: String, val sha256: String, val bytes: Long)

    sealed interface State {
        data object Idle : State
        data object UpToDate : State
        data class Available(val r: Release) : State
        data class Downloading(val r: Release, val progress: Float) : State
        data class Installing(val r: Release) : State
        data class Failed(val message: String, val r: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val versionName: String get() = BuildConfig.VERSION_NAME

    /** Every 6 hours when online, plus once now. Safe to call often. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(online).build(),
        )
        wm.enqueueUniqueWork("$WORK-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(online).build())
    }

    /** Checks, and installs a newer version when it's a safe moment. Returns false to retry later. */
    suspend fun run(ctx: Context, userAsked: Boolean = false, install: Boolean = true): Boolean {
        val s = _state.value
        if (s is State.Downloading || s is State.Installing) return true
        val r = try {
            latest()
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            if (userAsked) _state.value = State.Failed("Couldn't check. Is the watch online?", null)
            return false
        }
        if (r.code <= BuildConfig.VERSION_CODE) { _state.value = State.UpToDate; return true }
        _state.value = State.Available(r)
        if (!install) return true
        if (!userAsked && !safeMoment(ctx)) {
            Log.i(TAG, "${r.name} is ready; waiting for a safe moment to install")
            return true
        }
        return download(ctx, r)
    }

    /** Not during an SOS; and if protection is armed, only while charging (installing restarts the app). */
    private suspend fun safeMoment(ctx: Context): Boolean {
        val store = WatchStore.get(ctx)
        if (SosController.countdown.value != null) return false
        if (store.incidentNow()?.ended == false) return false
        val armed = store.safetyModeNow() || store.voiceSos.first() || store.fakeCallTwistNow()
        return !armed || ctx.getSystemService(BatteryManager::class.java)?.isCharging == true
    }

    /** The "Update now" button: download and install right away. */
    fun update(ctx: Context, r: Release) {
        val app = ctx.applicationContext
        Thread { download(app, r) }.start()
    }

    private fun download(ctx: Context, r: Release): Boolean {
        val app = ctx.applicationContext
        _state.value = State.Downloading(r, 0f)
        return try {
            val file = File(app.cacheDir, "update-${r.code}.apk")
            if (!(file.exists() && sha256(file) == r.sha256)) {
                app.cacheDir.listFiles { f -> f.name.startsWith("update") }?.forEach { it.delete() }
                val md = MessageDigest.getInstance("SHA-256")
                val c = (URL("${r.url}?v=${r.code}").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 60_000; useCaches = false
                }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.bytes
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); done += n
                            if (total > 0 && done - last > 256 * 1024) { last = done; _state.value = State.Downloading(r, done / total.toFloat()) }
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (r.sha256.isNotBlank() && sha != r.sha256) {
                    file.delete()
                    `in`.security4her.app.beta.WatchBeta.event("security", "critical", "update_fingerprint_mismatch", null)
                    _state.value = State.Failed("Download was damaged. Trying again later.", r)
                    return false
                }
            }
            _state.value = State.Installing(r)
            install(app, file)
            true
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            `in`.security4her.app.beta.WatchBeta.error("update_download_failed", e)
            _state.value = State.Failed("Download stopped. Trying again later.", r)
            false
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // Silent when this app installed itself last time (the phone installer arranges that).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val done = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(done.intentSender)
        }
    }

    internal fun onResult(ctx: Context, intent: Intent) {
        val r = (_state.value as? State.Installing)?.r
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants one tap to confirm (the first update of a copy installed some other way).
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (visible) runCatching { ctx.startActivity(confirm) } else askToConfirm(ctx, confirm, r)
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.UpToDate
            else -> {
                Log.w(TAG, "install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                `in`.security4her.app.beta.WatchBeta.event("error", "error", "update_install_failed", "status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                _state.value = State.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled." else "Update it from your phone: Security4Her → Me → Watch.",
                    r,
                )
            }
        }
    }

    private fun askToConfirm(ctx: Context, confirm: Intent, r: Release?) {
        val tap = PendingIntent.getActivity(ctx, 7, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, WatchApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Update ready")
            .setContentText("Tap to install Security4Her ${r?.name.orEmpty()}".trim())
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n) }
    }

    private fun latest(): Release {
        val c = (URL(BuildConfig.UPDATE_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection)
            .apply { connectTimeout = 15_000; readTimeout = 15_000; useCaches = false }
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("watch")
        return Release(
            o.getInt("versionCode"), o.getString("versionName"),
            URL(URL(BuildConfig.UPDATE_URL), o.getString("apk")).toString(), o.getString("sha256").lowercase(), o.optLong("bytes"),
        )
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = if (WatchUpdater.run(applicationContext)) Result.success() else Result.retry()
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = WatchUpdater.onResult(context, intent)
}

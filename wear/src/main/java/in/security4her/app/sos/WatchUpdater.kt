package `in`.security4her.app.sos

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import `in`.security4her.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates for the watch. Reads the "watch" entry of the site's version.json, downloads the APK over the
 * watch's own connection (Wi-Fi or the phone's Bluetooth proxy), checks its SHA-256 and installs it through
 * PackageInstaller. Android shows one confirmation on the watch; data and the phone link are kept.
 */
object WatchUpdater {
    private const val TAG = "S4hUpdate"
    private const val EVERY_MS = 6 * 60 * 60 * 1000L

    data class Release(val code: Int, val name: String, val url: String, val sha256: String, val bytes: Long)

    sealed interface State {
        data object Idle : State
        data object UpToDate : State
        data class Available(val r: Release) : State
        data class Downloading(val r: Release, val progress: Float) : State
        data class Installing(val r: Release) : State
        data class Failed(val message: String, val r: Release?) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastCheck = 0L

    val versionName: String get() = BuildConfig.VERSION_NAME

    fun check(ctx: Context, force: Boolean = false) {
        val s = _state.value
        if (s is State.Downloading || s is State.Installing) return
        if (!force && System.currentTimeMillis() - lastCheck < EVERY_MS) return
        lastCheck = System.currentTimeMillis()
        scope.launch {
            _state.value = try {
                val o = JSONObject(get(BuildConfig.UPDATE_URL + "?t=" + System.currentTimeMillis())).getJSONObject("watch")
                val r = Release(
                    o.getInt("versionCode"), o.getString("versionName"),
                    URL(URL(BuildConfig.UPDATE_URL), o.getString("apk")).toString(), o.getString("sha256").lowercase(), o.optLong("bytes"),
                )
                if (r.code > BuildConfig.VERSION_CODE) State.Available(r) else State.UpToDate
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                if (force) State.Failed("Couldn't check. Is the watch online?", null) else State.Idle
            }
        }
    }

    fun update(ctx: Context, r: Release) {
        val app = ctx.applicationContext
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        _state.value = State.Downloading(r, 0f)
        scope.launch {
            try {
                val file = File(app.cacheDir, "update.apk")
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
                    _state.value = State.Failed("Download was damaged. Try again.", r)
                    return@launch
                }
                _state.value = State.Installing(r)
                install(app, file)
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                _state.value = State.Failed("Download stopped. Try again.", r)
            }
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
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
                // Android wants a tap on the watch to confirm the update.
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.UpToDate
            else -> {
                Log.w(TAG, "install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                _state.value = State.Failed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled." else "This watch couldn't install it. Update it from a computer.",
                    r,
                )
            }
        }
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 15_000; useCaches = false }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = WatchUpdater.onResult(context, intent)
}

package `in`.security4her.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// ---------------------------------------------------------------- In-app updates
// The website publishes version.json next to the APK. The app compares versionCode, downloads the new APK itself,
// checks its SHA-256, then hands it to Android's installer. Same signing key, so it updates in place and keeps all data.

data class Release(val code: Int, val name: String, val url: String, val sha256: String, val bytes: Long, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val r: Release) : UpdateState
    data class Downloading(val r: Release, val progress: Float) : UpdateState
    data class Ready(val r: Release, val file: File) : UpdateState
    data class Failed(val message: String, val r: Release? = null) : UpdateState
}

object Updater {
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val versionName: String get() = BuildConfig.VERSION_NAME

    /** Checks the website for a newer version. [force] ignores the 6-hour spacing (the "Check for updates" button). */
    fun check(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val prefs = Store.prefs(app)
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) return
        if (!force && System.currentTimeMillis() - prefs.getLong("update_checked", 0) < EVERY_MS && s !is UpdateState.Idle) return
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = try {
                val json = get(BuildConfig.UPDATE_URL + "?t=" + System.currentTimeMillis())
                val o = JSONObject(json)
                val r = Release(
                    o.getInt("versionCode"), o.getString("versionName"),
                    URL(URL(BuildConfig.UPDATE_URL), o.getString("apk")).toString(),
                    o.getString("sha256").lowercase(), o.optLong("bytes"), o.optString("notes"),
                )
                prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply()
                if (r.code > BuildConfig.VERSION_CODE) {
                    AppLog.info("Update", "Version ${r.name} is available (this is $versionName)")
                    UpdateState.Available(r)
                } else UpdateState.UpToDate
            } catch (e: Exception) {
                if (force) AppLog.warn("Update", "Couldn't check for updates", e)
                if (force) UpdateState.Failed("Couldn't reach the update server. Check your internet and try again.") else UpdateState.Idle
            }
        }
    }

    /** Downloads the APK with progress, verifies its fingerprint, then opens the installer. */
    fun download(ctx: Context, r: Release) {
        val app = ctx.applicationContext
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(r, 0f)
        scope.launch {
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val file = File(dir, "Security4Her-${r.name}.apk")
                val md = MessageDigest.getInstance("SHA-256")
                val c = (URL("${r.url}?v=${r.code}").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; useCaches = false
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
                            if (total > 0 && done - last > 256 * 1024) { last = done; _state.value = UpdateState.Downloading(r, done / total.toFloat()) }
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (r.sha256.isNotBlank() && sha != r.sha256) {
                    file.delete()
                    AppLog.error("Update", "Downloaded file didn't match its fingerprint; discarded")
                    _state.value = UpdateState.Failed("The download was damaged, so it wasn't installed. Try again.", r)
                    return@launch
                }
                AppLog.ok("Update", "Downloaded ${r.name} and checked its fingerprint")
                _state.value = UpdateState.Ready(r, file)
                withContext(Dispatchers.Main) { install(app, file) }
            } catch (e: Exception) {
                AppLog.warn("Update", "Update download failed", e)
                _state.value = UpdateState.Failed("The download stopped. Check your internet and try again.", r)
            }
        }
    }

    /** True when Android already lets this app open the installer. */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun install(ctx: Context, file: File) {
        if (!canInstall(ctx)) {
            // One time only: Android asks to allow installs from Security4Her.
            runCatching {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { AppLog.warn("Update", "Couldn't open the installer", it) }
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000; useCaches = false }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}

// ---------------------------------------------------------------- UI

/** Shown on Home only when there's something to do: a new version, a download in progress, or one ready to install. */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s by Updater.state.collectAsStateWithLifecycle()
    val r = when (val x = s) {
        is UpdateState.Available -> x.r
        is UpdateState.Downloading -> x.r
        is UpdateState.Ready -> x.r
        is UpdateState.Failed -> x.r
        else -> null
    } ?: return
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Update to ${r.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val x = s) {
                        is UpdateState.Downloading -> "Downloading ${(x.progress * 100).toInt()}%"
                        is UpdateState.Ready -> "Downloaded. Tap Update on the next screen."
                        is UpdateState.Failed -> x.message
                        else -> r.notes.ifBlank { "A newer version of Security4Her is ready." }
                    },
                    color = if (s is UpdateState.Failed) SosRed else Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (s is UpdateState.Downloading) {
            Spacer(Modifier.height(12.dp))
            Pill((s as UpdateState.Downloading).progress, Modifier.fillMaxWidth().height(10.dp))
        } else {
            Spacer(Modifier.height(12.dp))
            ChunkyButton(
                when (s) { is UpdateState.Ready -> "Install update"; is UpdateState.Failed -> "Try again"; else -> "Update now" },
            ) {
                when (val x = s) {
                    is UpdateState.Ready -> Updater.install(ctx, x.file)
                    else -> Updater.download(ctx, r)
                }
            }
            if (!Updater.canInstall(ctx)) Text(
                "The first time, Android asks you to allow installs from Security4Her. Turn it on, then come back and tap Install update.",
                color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Version and a manual check, for the Me screen. */
@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val s by Updater.state.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Version ${Updater.versionName}")
            Text(
                when (val x = s) {
                    UpdateState.Checking -> "Checking for updates…"
                    UpdateState.UpToDate -> "You have the latest version."
                    is UpdateState.Available -> "Version ${x.r.name} is available."
                    is UpdateState.Downloading -> "Downloading ${(x.progress * 100).toInt()}%"
                    is UpdateState.Ready -> "Ready to install."
                    is UpdateState.Failed -> x.message
                    UpdateState.Idle -> "Updates are checked automatically."
                },
                color = if (s is UpdateState.Failed) SosRed else Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = {
            when (val x = s) {
                is UpdateState.Available -> Updater.download(ctx, x.r)
                is UpdateState.Ready -> Updater.install(ctx, x.file)
                else -> Updater.check(ctx, force = true)
            }
        }, enabled = s !is UpdateState.Checking && s !is UpdateState.Downloading) {
            Text(
                when (s) { is UpdateState.Available -> "Update"; is UpdateState.Ready -> "Install"; else -> "Check" },
                color = Lilac,
            )
        }
    }
}

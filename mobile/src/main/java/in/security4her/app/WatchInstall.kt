package `in`.security4her.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.sos.WearBridge
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// ---------------------------------------------------------------- Install the watch app from the phone
// Wear OS has no "open this APK" on the watch, so the phone does what a computer would: it pairs with the watch's
// Wireless debugging (the 6-digit code), downloads the watch APK from the website, checks its SHA-256 and streams it
// into the watch's package installer. It then lets the watch install its own updates, so this is needed only once.

private const val WATCH_PKG = "in.security4her.app"

/** A watch advertising Wireless debugging on this Wi-Fi: the pairing dialog, or the ready-to-connect service. */
data class WatchService(val name: String, val host: String, val port: Int, val pairing: Boolean)

enum class InstallStep(val label: String) { Connect("Connect to your watch"), Download("Download the watch app"), Install("Install on the watch"), Finish("Turn on automatic updates") }

sealed interface WatchInstallState {
    data object Idle : WatchInstallState
    data class Working(val step: InstallStep, val progress: Float? = null, val note: String = "") : WatchInstallState
    data class Done(val version: String, val wasInstalled: String?) : WatchInstallState
    data class Failed(val step: InstallStep, val message: String, val needsPairing: Boolean = false) : WatchInstallState
}

object WatchInstaller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<WatchInstallState>(WatchInstallState.Idle)
    val state: StateFlow<WatchInstallState> = _state.asStateFlow()

    fun reset() { if (_state.value !is WatchInstallState.Working) _state.value = WatchInstallState.Idle }

    /**
     * Pairs (when [pairPort] and [code] are given), connects to [host]:[connectPort] (or finds the port on Wi-Fi),
     * then installs the latest watch app.
     */
    fun start(ctx: Context, host: String, connectPort: Int?, pairPort: Int? = null, code: String? = null, finder: WatchFinder? = null) {
        if (_state.value is WatchInstallState.Working) return
        val app = ctx.applicationContext
        _state.value = WatchInstallState.Working(InstallStep.Connect, note = if (code != null) "Pairing…" else "Connecting…")
        scope.launch {
            var step = InstallStep.Connect
            val adb = AdbManager(app)
            try {
                if (pairPort != null && code != null) {
                    val ok = runCatching { adb.pair(host, pairPort, code) }.getOrElse { false }
                    if (!ok) {
                        _state.value = WatchInstallState.Failed(step, "The code didn't work. Tap Pair new device on the watch again and enter the new code.", needsPairing = true)
                        return@launch
                    }
                    AppLog.ok("Watch install", "Paired with the watch at $host")
                    _state.value = WatchInstallState.Working(step, note = "Paired. Connecting…")
                }
                // After pairing the watch advertises its connect port; wait for it if we don't know it yet.
                val port = connectPort ?: withTimeoutOrNull(15_000) {
                    var p: Int? = null
                    while (p == null) {
                        p = finder?.services?.value?.values?.firstOrNull { !it.pairing && it.host == host }?.port
                        if (p == null) delay(300)
                    }
                    p
                }
                if (port == null) {
                    _state.value = WatchInstallState.Failed(step, "Paired, but the watch didn't answer. Keep Wireless debugging open on the watch and try again.")
                    return@launch
                }
                try {
                    if (!adb.connect(host, port)) throw IllegalStateException("not connected")
                    adb.shell("echo ok") // an unpaired phone gets through the handshake, then the watch hangs up
                } catch (e: Exception) {
                    if (code != null && e !is AdbPairingRequiredException) throw e
                    AppLog.info("Watch install", "The watch doesn't know this phone yet; asking to pair")
                    _state.value = WatchInstallState.Failed(step, "First time? On the watch tap Pair new device, then enter the code here.", needsPairing = true)
                    return@launch
                }
                val model = adb.shell("getprop ro.product.model").trim().ifBlank { "your watch" }
                val installed = Regex("versionName=([^\\s]+)").find(adb.shell("dumpsys package $WATCH_PKG | grep versionName"))?.groupValues?.get(1)
                val installedCode = Regex("versionCode=(\\d+)").find(adb.shell("dumpsys package $WATCH_PKG | grep versionCode"))?.groupValues?.get(1)?.toIntOrNull()
                AppLog.ok("Watch install", "Connected to $model" + (installed?.let { ", which has $it" } ?: ""))

                step = InstallStep.Download
                _state.value = WatchInstallState.Working(step, 0f, "From security4her.vercel.app")
                val rel = latestWatchRelease()
                val needsInstall = installedCode == null || installedCode < rel.code
                if (needsInstall) {
                    val apk = download(app, rel) { p -> _state.value = WatchInstallState.Working(step, p, "Version ${rel.name}") }
                    step = InstallStep.Install
                    _state.value = WatchInstallState.Working(step, 0f, "Sending to $model")
                    val out = adb.install(apk) { p -> _state.value = WatchInstallState.Working(step, p, "Sending to $model") }
                    if (!out.contains("Success")) throw InstallError(out)
                    AppLog.ok("Watch install", "Installed ${rel.name} on the watch")
                }

                step = InstallStep.Finish
                _state.value = WatchInstallState.Working(step, null, "Almost done")
                // Let the watch install its own updates from now on, and open the app on it.
                adb.shell("appops set $WATCH_PKG REQUEST_INSTALL_PACKAGES allow")
                adb.shell("am start -n $WATCH_PKG/.MainActivity")
                runCatching { WearBridge.linkWatch(app) }
                _state.value = WatchInstallState.Done(rel.name, installed)
            } catch (e: InstallError) {
                AppLog.warn("Watch install", "Install refused: ${e.out.trim()}")
                _state.value = WatchInstallState.Failed(step, when {
                    "UPDATE_INCOMPATIBLE" in e.out -> "The watch has a different copy of Security4Her. Uninstall it on the watch, then try again."
                    "INSUFFICIENT_STORAGE" in e.out -> "The watch is out of space. Remove an app or two, then try again."
                    else -> "The watch said: ${e.out.trim().take(120)}"
                })
            } catch (e: Exception) {
                AppLog.warn("Watch install", "Watch install stopped at ${step.name}", e)
                _state.value = WatchInstallState.Failed(step, when (step) {
                    InstallStep.Connect -> "Couldn't reach the watch. Check both are on the same Wi-Fi and Wireless debugging is on."
                    InstallStep.Download -> "Couldn't download the watch app. Check this phone's internet."
                    else -> "The connection to the watch dropped. Keep the watch awake and try again."
                })
            } finally {
                runCatching { adb.close() }
            }
        }
    }

    private class InstallError(val out: String) : Exception(out)

    private data class WatchRelease(val code: Int, val name: String, val url: String, val sha256: String)

    private fun latestWatchRelease(): WatchRelease {
        val c = (URL(BuildConfig.UPDATE_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection)
            .apply { connectTimeout = 10_000; readTimeout = 10_000; useCaches = false }
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("watch")
        return WatchRelease(o.getInt("versionCode"), o.getString("versionName"), URL(URL(BuildConfig.UPDATE_URL), o.getString("apk")).toString(), o.getString("sha256").lowercase())
    }

    /** Downloads the watch APK (reusing a verified copy from an earlier try) and checks its SHA-256. */
    private fun download(ctx: Context, r: WatchRelease, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "watch").apply { mkdirs() }
        val file = File(dir, "Security4Her-watch-${r.name}.apk")
        if (file.exists() && sha256(file) == r.sha256) { onProgress(1f); return file }
        dir.listFiles()?.forEach { it.delete() }
        val md = MessageDigest.getInstance("SHA-256")
        val c = (URL("${r.url}?v=${r.code}").openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; useCaches = false }
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) onProgress(done / total.toFloat())
                }
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256.isNotBlank() && sha != r.sha256) { file.delete(); throw IllegalStateException("fingerprint mismatch") }
        AppLog.ok("Watch install", "Downloaded watch app ${r.name} and checked its fingerprint")
        return file
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** This phone's adb identity: an RSA key and certificate made once and kept, so the watch remembers the pairing. */
private class AdbManager(ctx: Context) : AbsAdbConnectionManager() {
    private val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
    private val key: PrivateKey
    private val cert: Certificate

    init {
        // The engine socket deadlocks when adb reads and writes on two threads at once; the fd socket doesn't.
        org.conscrypt.Conscrypt.setUseEngineSocketByDefault(false)
        setApi(30) // the watch app needs Wear OS 3 (Android 11) or later
        setTimeout(20, TimeUnit.SECONDS)
        val kf = File(dir, "key.pk8"); val cf = File(dir, "cert.der")
        if (!kf.exists() || !cf.exists()) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Security4Her phone")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 25L * 365 * 86_400_000L), name, kp.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            kf.writeBytes(kp.private.encoded)
            cf.writeBytes(JcaX509CertificateConverter().getCertificate(holder).encoded)
        }
        key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
        cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
    }

    override fun getPrivateKey() = key
    override fun getCertificate() = cert
    override fun getDeviceName() = "Security4Her"

    fun shell(cmd: String): String = openStream("shell:$cmd").use { readAll(it, 15_000) }

    /**
     * Reads until the watch closes the stream. libadb's own blocking read can wait forever when the last output and
     * the close arrive together (it marks the close "pending" and never finishes it), so this polls instead.
     */
    private fun readAll(s: AdbStream, timeoutMs: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val avail = try { s.available() } catch (e: java.io.IOException) { break } // closed and drained
            when {
                avail > 0 -> { val n = s.read(buf, 0, minOf(avail, buf.size)); if (n < 0) break; out.write(buf, 0, n) }
                s.isClosed || pendingClose.getBoolean(s) -> break
                System.currentTimeMillis() > deadline -> break
                else -> Thread.sleep(15)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        val pendingClose: java.lang.reflect.Field = AdbStream::class.java.getDeclaredField("mPendingClose").apply { isAccessible = true }
    }

    /** Streams the APK into `cmd package install`, like `adb install` does. Returns the installer's reply. */
    fun install(apk: File, onProgress: (Float) -> Unit): String {
        val s = openStream("exec:cmd package install -S ${apk.length()}")
        s.use {
            val out = s.openOutputStream()
            apk.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); done += n
                    onProgress(done / apk.length().toFloat())
                }
            }
            out.flush()
            return readAll(s, 180_000)
        }
    }
}

// ---------------------------------------------------------------- Finding the watch on Wi-Fi

/** Watches for Wireless debugging on this Wi-Fi (mDNS), skipping this phone's own. */
class WatchFinder(ctx: Context) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private val _services = MutableStateFlow<Map<String, WatchService>>(emptyMap())
    val services: StateFlow<Map<String, WatchService>> = _services.asStateFlow()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun start() {
        for (type in listOf(PAIR, CONNECT)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) { queue.trySend(info) }
                override fun onServiceLost(info: NsdServiceInfo) { _services.update { it - key(info) } }
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners += l }
        }
        // Android resolves one service at a time
        scope.launch { for (info in queue) resolve(info)?.let { s -> _services.update { it + (key(info) to s) } } }
    }

    fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear(); queue.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun key(i: NsdServiceInfo) = i.serviceType.trim('.') + "/" + i.serviceName

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): WatchService? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val addr = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses.firstOrNull { it is Inet4Address } ?: i.host else i.host
                        val host = addr?.hostAddress
                        val s = if (host == null || host in ownAddresses()) null
                        else WatchService(i.serviceName, host, i.port, pairing = i.serviceType.contains("pairing"))
                        if (cont.isActive) cont.resume(s)
                    }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { n -> n.inetAddresses.toList().mapNotNull { it.hostAddress } }.toSet()
    }.getOrDefault(emptySet())

    companion object {
        const val PAIR = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"

        fun onWifi(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }
    }
}

// ---------------------------------------------------------------- UI

@Composable
fun WatchInstallScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val state by WatchInstaller.state.collectAsStateWithLifecycle()
    val finder = remember { WatchFinder(ctx.applicationContext) }
    DisposableEffect(Unit) { finder.start(); onDispose { finder.stop(); WatchInstaller.reset() } }
    val services by finder.services.collectAsStateWithLifecycle()
    val pairing = services.values.firstOrNull { it.pairing }
    val ready = services.values.firstOrNull { !it.pairing }
    var wifi by remember { mutableStateOf(WatchFinder.onWifi(ctx)) }
    LaunchedEffect(Unit) { while (true) { wifi = WatchFinder.onWifi(ctx); delay(2_000) } }
    var code by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    val working = state is WatchInstallState.Working
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Muted)
        }
        Spacer(Modifier.height(8.dp))
        Text("Install on your watch", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your phone puts the watch app on your watch over Wi-Fi. You do this once: after that the watch keeps itself up to date.",
            color = Muted, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))

        when (val s = state) {
            is WatchInstallState.Working, is WatchInstallState.Done -> Progress(s, onClose)
            else -> {
                val failed = s as? WatchInstallState.Failed
                // Six digits typed: go. No extra button to find. Each code is tried once; a new code tries again.
                LaunchedEffect(code, pairing) {
                    if (code.length == 6 && pairing != null && code != tried) {
                        tried = code
                        WatchInstaller.start(ctx, pairing.host, null, pairing.port, code, finder)
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).padding(18.dp).animateContentSize()) {
                    when {
                        !wifi -> Status("Connect this phone to Wi-Fi", "Use the same Wi-Fi network as your watch.", warn = true)
                        pairing != null -> {
                            Status("Watch found", "Type the 6-digit code it shows. Installing starts by itself.", good = true)
                            Spacer(Modifier.height(14.dp))
                            Field(code, { code = it.filter(Char::isDigit).take(6) }, "Wi-Fi pairing code", KeyboardType.Number)
                            if (failed != null && code.length == 6) {
                                Spacer(Modifier.height(14.dp))
                                ChunkyButton("Try again") { WatchInstaller.start(ctx, pairing.host, null, pairing.port, code, finder) }
                            }
                        }
                        ready != null -> {
                            Status("Found your watch", "If you paired before, install now. First time? Tap Pair new device on the watch.", good = true)
                            Spacer(Modifier.height(14.dp))
                            ChunkyButton("Install on watch") { WatchInstaller.start(ctx, ready.host, ready.port, finder = finder) }
                        }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = Lilac, strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Looking for your watch…", style = MaterialTheme.typography.titleMedium)
                                Text("Turn on Wireless debugging on the watch and tap Pair new device.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    failed?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it.message, color = SosRed, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                GetReady(expanded = pairing == null && ready == null)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { manual = !manual }) { Text(if (manual) "Hide address entry" else "Watch not found? Enter its address", color = Lilac) }
                if (manual) ManualEntry(enabled = !working) { host, port, pairPort, c -> WatchInstaller.start(ctx, host, port, pairPort, c, finder) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The one-time watch settings. Open while the phone is still looking; folded away once the watch is found. */
@Composable
private fun GetReady(expanded: Boolean) {
    var open by remember(expanded) { mutableStateOf(expanded) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).clickable { open = !open }.padding(18.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Get the watch ready (once)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", color = Lilac, style = MaterialTheme.typography.labelLarge)
        }
        if (open) listOf(
            "Connect the watch to the same Wi-Fi as this phone.",
            "Open Settings → System → About. Tap Build number (on Galaxy Watch: Software version) until it says you're a developer.",
            "Go back to Settings → Developer options. Turn on ADB debugging, then Wireless debugging.",
            "Tap Pair new device. Keep that screen open; a code appears.",
        ).forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = Lilac, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun Status(title: String, body: String, good: Boolean = false, warn: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (good) LiveGreen else if (warn) Amber else Muted))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ManualEntry(enabled: Boolean, onGo: (String, Int?, Int?, String?) -> Unit) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("The watch shows these under Wireless debugging.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Field(ip, { ip = it.filter { c -> c.isDigit() || c == '.' } }, "IP address, like 192.168.1.23", KeyboardType.Decimal)
        Field(port, { port = it.filter(Char::isDigit).take(5) }, "Port (from IP address & Port)", KeyboardType.Number)
        Text("First time only, from Pair new device:", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Field(pairPort, { pairPort = it.filter(Char::isDigit).take(5) }, "Pairing port", KeyboardType.Number) }
            Box(Modifier.weight(1f)) { Field(code, { code = it.filter(Char::isDigit).take(6) }, "Code", KeyboardType.Number) }
        }
        val pairing = pairPort.isNotBlank() && code.length == 6
        ChunkyButton(if (pairing) "Pair and install" else "Install", tone = Tone.Plain, enabled = enabled && ip.count { it == '.' } == 3 && (port.isNotBlank() || pairing)) {
            onGo(ip, port.toIntOrNull(), pairPort.toIntOrNull().takeIf { pairing }, code.takeIf { pairing })
        }
    }
}

@Composable
private fun Progress(s: WatchInstallState, onDone: () -> Unit) {
    val done = s as? WatchInstallState.Done
    val w = s as? WatchInstallState.Working
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InstallStep.entries.forEach { step ->
            val state = when {
                done != null -> 2
                w == null -> 0
                step.ordinal < w.step.ordinal -> 2
                step == w.step -> 1
                else -> 0
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(if (state == 2) Lilac else Surface2), contentAlignment = Alignment.Center) {
                    when (state) {
                        2 -> Icon(Icons.Filled.Check, null, tint = OnLilac, modifier = Modifier.size(18.dp))
                        1 -> CircularProgressIndicator(Modifier.size(18.dp), color = Lilac, strokeWidth = 2.dp)
                        else -> Text("${step.ordinal + 1}", color = Muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.label, style = MaterialTheme.typography.titleMedium, color = if (state == 0) Muted else Ink)
                    if (state == 1 && w != null) {
                        if (w.note.isNotBlank()) Text(w.note + (w.progress?.let { "  ·  ${(it * 100).toInt()}%" } ?: ""), color = Muted, style = MaterialTheme.typography.bodySmall)
                        w.progress?.let { Spacer(Modifier.height(6.dp)); Pill(it, Modifier.fillMaxWidth().height(8.dp)) }
                    }
                }
            }
        }
    }
    done?.let {
        Spacer(Modifier.height(16.dp))
        Tip(
            if (it.wasInstalled == it.version) "Your watch already has ${it.version}. It will now update itself."
            else "Security4Her ${it.version} is on your watch. From now on the watch updates itself.",
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Now turn Wireless debugging off on the watch (Settings, then Developer options). Left on, it lets paired devices control the watch. Updates don't need it.",
                color = Amber, style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(16.dp))
        ChunkyButton("Done", onClick = onDone)
    }
}

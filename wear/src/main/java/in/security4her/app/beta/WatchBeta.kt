package `in`.security4her.app.beta

import android.content.Context
import android.os.Build
import `in`.security4her.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Beta test data from the watch: one yes/no agreement on the watch itself. No location, audio or account details are
 * ever recorded; events are fixed codes plus technical error text. Same private database and rules as the phone.
 */
object WatchBeta {
    const val CONSENT_VERSION = 1
    private val CATEGORIES = listOf("crash", "error", "security", "false_alarm", "usage", "performance", "device")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("s4h_beta", Context.MODE_PRIVATE)
    private val queue by lazy { File(app.filesDir, "beta-queue.jsonl") }

    private val _agreed = MutableStateFlow<Boolean?>(null) // null: not asked yet
    val agreed: StateFlow<Boolean?> = _agreed.asStateFlow()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (prefs.getString("tester", null) == null) prefs.edit().putString("tester", hex(16)).putString("key", hex(32)).apply()
        _agreed.value = if (prefs.getInt("consent_v", 0) >= CONSENT_VERSION) prefs.getBoolean("agreed", false) else null
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val top = e.stackTrace.firstOrNull { it.className.startsWith("in.security4her") }?.toString()
                event("crash", "critical", "crash_${e.javaClass.simpleName}",
                    "${e.javaClass.name}: ${e.message}\n" + e.stackTrace.take(25).joinToString("\n") { "  at $it" }, mapOf("thread" to t.name, "top_frame" to top))
            }
            previous?.uncaughtException(t, e)
        }
        if (_agreed.value == true && prefs.getString("device_sent", null) != BuildConfig.VERSION_NAME) {
            event("device", "info", "device_profile", null, mapOf("brand" to Build.BRAND, "model" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT))
            prefs.edit().putString("device_sent", BuildConfig.VERSION_NAME).apply()
        }
        flush()
    }

    fun decide(agree: Boolean) {
        prefs.edit().putInt("consent_v", CONSENT_VERSION).putBoolean("agreed", agree).putBoolean("synced", false).apply()
        _agreed.value = agree
        if (!agree) forget() else flush()
    }

    /** Turning sharing off deletes everything this watch sent. */
    private fun forget() {
        runCatching { queue.delete() }
        scope.launch {
            val cfg = config() ?: return@launch
            post("${cfg.first}/rest/v1/rpc/beta_forget", cfg.second, JSONObject().put("p_tester", tester()).put("p_key", prefs.getString("key", "")).toString())
        }
    }

    private fun tester() = prefs.getString("tester", "")!!

    /** Queue an event. Never pass location, names or numbers in [message] or [detail]. */
    fun event(category: String, severity: String, code: String, message: String? = null, detail: Map<String, Any?> = emptyMap()) {
        if (!::app.isInitialized || _agreed.value != true || category !in CATEGORIES) return
        val o = JSONObject().put("happened_at", iso()).put("tester", tester()).put("app", "watch").put("app_version", BuildConfig.VERSION_NAME)
            .put("category", category).put("severity", severity).put("code", code.lowercase().replace(Regex("[^a-z0-9_.:-]"), "_").take(64))
            .put("fingerprint", sha256("$category|$code|${detail["top_frame"] ?: message?.lines()?.first()?.replace(Regex("\\d+"), "#")?.take(160)}").take(24))
            .put("message", message?.take(4000) ?: JSONObject.NULL)
            .put("detail", if (detail.isEmpty()) JSONObject.NULL else JSONObject(detail))
            .put("device", JSONObject().put("brand", Build.BRAND).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT))
        synchronized(this) {
            runCatching {
                queue.appendText(o.toString() + "\n")
                val lines = queue.readLines().filter { it.isNotBlank() }
                if (lines.size > 1000) queue.writeText(lines.takeLast(1000).joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun error(code: String, e: Throwable?) = event("error", "error", code, e?.let { "${it.javaClass.name}: ${it.message}" })

    private var flushing = false
    fun flush() {
        if (!::app.isInitialized) return
        scope.launch {
            synchronized(this@WatchBeta) { if (flushing) return@launch; flushing = true }
            try { upload() } catch (_: Exception) {} finally { flushing = false }
        }
    }

    private fun upload() {
        if (_agreed.value != true) return
        val cfg = config() ?: return
        if (!prefs.getBoolean("synced", false)) {
            val body = JSONObject().put("p_tester", tester()).put("p_app", "watch").put("p_version", CONSENT_VERSION)
                .put("p_categories", JSONArray(CATEGORIES)).put("p_key_hash", sha256(prefs.getString("key", "")!!))
            if (post("${cfg.first}/rest/v1/rpc/beta_set_consent", cfg.second, body.toString()) !in 200..299) return
            prefs.edit().putBoolean("synced", true).apply()
        }
        while (true) {
            val lines = synchronized(this) { if (queue.exists()) queue.readLines().filter { it.isNotBlank() } else emptyList() }
            if (lines.isEmpty()) return
            val batch = lines.take(50)
            val code = post("${cfg.first}/rest/v1/beta_events", cfg.second, batch.joinToString(",", "[", "]"))
            if (code == 429 || code >= 500 || code < 0) return
            synchronized(this) {
                val now = if (queue.exists()) queue.readLines().filter { it.isNotBlank() } else emptyList()
                queue.writeText(now.drop(batch.size).joinToString("\n", postfix = if (now.size > batch.size) "\n" else ""))
            }
        }
    }

    private fun config(): Pair<String, String>? {
        val every = if (prefs.getString("url", "").isNullOrBlank()) 15 * 60_000L else 6 * 3600_000L
        if (System.currentTimeMillis() - prefs.getLong("cfg_at", 0) > every) runCatching {
            val c = (URL(BuildConfig.UPDATE_URL + "?b=" + System.currentTimeMillis()).openConnection() as HttpURLConnection)
                .apply { connectTimeout = 15_000; readTimeout = 15_000; useCaches = false }
            val beta = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONObject("beta")
            prefs.edit().putString("url", beta?.optString("url").orEmpty().trimEnd('/')).putString("anon", beta?.optString("key").orEmpty())
                .putLong("cfg_at", System.currentTimeMillis()).apply()
        }
        val url = prefs.getString("url", "").orEmpty(); val key = prefs.getString("anon", "").orEmpty()
        return if (url.startsWith("https://") && key.isNotBlank()) url to key else null
    }

    private fun post(url: String, key: String, json: String): Int = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("apikey", key); setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Prefer", "return=minimal")
        }
        c.outputStream.use { it.write(json.toByteArray()) }
        c.responseCode
    } catch (e: Exception) { -1 }

    private fun hex(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun iso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}

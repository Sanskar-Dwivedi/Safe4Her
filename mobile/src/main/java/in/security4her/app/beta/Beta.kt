package `in`.security4her.app.beta

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import `in`.security4her.app.BuildConfig
import `in`.security4her.app.core.Store
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

// ---------------------------------------------------------------- Beta test data
// Only after the tester agrees, and only in the categories she chose. Everything is scrubbed on the phone before it
// is queued: no location, phone numbers, names, messages, recordings or Aadhaar ever leave. A random tester ID
// links her events; her private delete key lets her erase them. The server re-checks consent on every insert.

enum class BetaCategory(val code: String, val title: String, val what: String) {
    CRASH("crash", "Crash reports", "When the app closes unexpectedly: which screen and the technical error."),
    ERROR("error", "Errors and warnings", "Things that went wrong, such as a text that failed or GPS that didn't respond."),
    SECURITY("security", "Security events", "A wrong safety PIN, a recording that fails its integrity check, a damaged download."),
    FALSE_ALARM("false_alarm", "False alarms", "An SOS or knock alert that you cancelled, so we can make detection more accurate."),
    USAGE("usage", "Feature use", "Which features you open, such as SOS, Safe Journey or Fake call. Never what you type."),
    PERFORMANCE("performance", "Speed", "How long things take, such as app start and the time from SOS to the first text."),
    DEVICE("device", "Phone model", "Brand, model and Android version, to find problems on particular phones."),
}

object Beta {
    const val CONSENT_VERSION = 1
    private const val APP = "phone"
    private const val MAX_QUEUE = 2000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private val queueFile by lazy { File(app.filesDir, "beta-queue.jsonl") }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** What the UI shows: whether she decided, what she allowed, and how much is waiting to upload. */
    data class Snapshot(val decided: Boolean = false, val agreed: Boolean = false, val categories: Set<BetaCategory> = emptySet(),
                        val pending: Int = 0, val sent: Int = 0, val tester: String = "", val serverReady: Boolean = false)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val p = Store.prefs(app)
        if (p.getString("beta_tester", null) == null) {
            p.edit().putString("beta_tester", hex(16)).putString("beta_key", hex(32)).apply()
        }
        refresh()
        installCrashHandler()
        measureStart(app as Application)
        if (allowed(BetaCategory.DEVICE) && p.getString("beta_device_sent", null) != BuildConfig.VERSION_NAME) {
            record(BetaCategory.DEVICE, "info", "device_profile", null, deviceInfo())
            p.edit().putString("beta_device_sent", BuildConfig.VERSION_NAME).apply()
        }
        flush()
    }

    // ---- consent

    val needsDecision: Boolean get() = Store.prefs(app).getInt("beta_consent_v", 0) < CONSENT_VERSION

    fun accept(cats: Set<BetaCategory>) {
        Store.prefs(app).edit().putInt("beta_consent_v", CONSENT_VERSION).putBoolean("beta_agreed", true)
            .putString("beta_cats", cats.joinToString(",") { it.code }).putBoolean("beta_consent_synced", false)
            .putLong("beta_consent_at", System.currentTimeMillis()).apply()
        refresh()
        if (allowed(BetaCategory.DEVICE)) {
            record(BetaCategory.DEVICE, "info", "device_profile", null, deviceInfo())
            Store.prefs(app).edit().putString("beta_device_sent", BuildConfig.VERSION_NAME).apply()
        }
        flush()
    }

    fun decline() {
        Store.prefs(app).edit().putInt("beta_consent_v", CONSENT_VERSION).putBoolean("beta_agreed", false)
            .putString("beta_cats", "").putBoolean("beta_consent_synced", false).apply()
        runCatching { queueFile.delete() }
        refresh(); flush()
    }

    /** Withdraw and erase everything sent so far. Returns a message for the UI. */
    fun forget(done: (String) -> Unit) {
        scope.launch {
            val p = Store.prefs(app)
            val cfg = config()
            val msg = if (cfg == null) "Your test data was only on this phone, and it's now deleted." else try {
                val body = JSONObject().put("p_tester", tester()).put("p_key", p.getString("beta_key", ""))
                val (code, text) = post("${cfg.first}/rest/v1/rpc/beta_forget", cfg.second, body.toString())
                if (code in 200..299) "Deleted ${text.trim().toIntOrNull() ?: 0} records from the test server." else "Couldn't reach the test server. Try again with internet."
            } catch (e: Exception) { "Couldn't reach the test server. Try again with internet." }
            runCatching { queueFile.delete() }
            p.edit().putInt("beta_consent_v", CONSENT_VERSION).putBoolean("beta_agreed", false).putString("beta_cats", "").apply()
            refresh()
            done(msg)
        }
    }

    fun allowed(c: BetaCategory): Boolean = state.value.agreed && c in state.value.categories

    private fun tester() = Store.prefs(app).getString("beta_tester", "")!!

    private fun refresh() {
        val p = Store.prefs(app)
        val cats = p.getString("beta_cats", "").orEmpty().split(',').mapNotNull { c -> BetaCategory.entries.firstOrNull { it.code == c } }.toSet()
        _state.value = Snapshot(
            decided = p.getInt("beta_consent_v", 0) >= CONSENT_VERSION, agreed = p.getBoolean("beta_agreed", false), categories = cats,
            pending = runCatching { if (queueFile.exists()) queueFile.readLines().count { it.isNotBlank() } else 0 }.getOrDefault(0),
            sent = p.getInt("beta_sent", 0), tester = tester(), serverReady = p.getString("beta_url", "").orEmpty().isNotBlank(),
        )
    }

    // ---- recording

    /** Queue one event if she allowed its category. [detail] values must already be free of personal data. */
    fun record(cat: BetaCategory?, severity: String, code: String, message: String?, detail: Map<String, Any?> = emptyMap(), category: String? = null) {
        if (!::app.isInitialized) return
        val catCode = category ?: cat?.code ?: return
        if (catCode != "feedback" && (cat == null || !allowed(cat))) return
        if (catCode == "feedback" && !state.value.agreed) return
        val clean = message?.let { scrub(it) }
        val o = JSONObject()
            .put("happened_at", iso(System.currentTimeMillis())).put("tester", tester()).put("app", APP)
            .put("app_version", BuildConfig.VERSION_NAME).put("category", catCode).put("severity", severity)
            .put("code", code.lowercase().replace(Regex("[^a-z0-9_.:-]"), "_").take(64))
            .put("fingerprint", fingerprint(catCode, code, clean, detail["top_frame"] as? String))
            .put("message", clean?.take(4000) ?: JSONObject.NULL)
            .put("detail", if (detail.isEmpty()) JSONObject.NULL else JSONObject(detail.mapValues { (_, v) -> if (v is String) scrub(v) else v }))
            .put("device", if (allowed(BetaCategory.DEVICE)) JSONObject().put("brand", Build.BRAND).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT) else JSONObject.NULL)
        append(o)
    }

    @Synchronized
    private fun append(o: JSONObject) {
        runCatching {
            queueFile.appendText(o.toString() + "\n")
            val lines = queueFile.readLines().filter { it.isNotBlank() }
            if (lines.size > MAX_QUEUE) queueFile.writeText(lines.takeLast(MAX_QUEUE).joinToString("\n", postfix = "\n"))
        }
        refresh()
    }

    /** She reports a problem herself. */
    fun feedback(kind: String, severity: String, text: String, attachRecentErrors: Boolean) {
        val recent = if (attachRecentErrors) `in`.security4her.app.core.AppLog.entries.value
            .filter { it.level == "warn" || it.level == "error" }.takeLast(20)
            .joinToString("\n") { "${it.tag}: ${scrub(it.msg)}" } else null
        record(null, severity, "report_$kind", text, mapOfNotNull("recent_errors" to recent, "screen_dp" to screenDp()), category = "feedback")
        flush()
    }

    // ---- what the app already logs, mapped to safe event codes

    private var sosStartedAt = 0L

    /** Called for every AppLog line. Info lines become fixed codes (never their text); warnings and errors are scrubbed. */
    fun fromLog(level: String, tag: String, msg: String) {
        if (!::app.isInitialized || !state.value.agreed) return
        val usage = USAGE_CODES.firstOrNull { (t, prefix, _) -> t == tag && msg.startsWith(prefix) }
        when {
            tag == "SOS" && msg.startsWith("SOS started (") -> {
                sosStartedAt = SystemClock.elapsedRealtime()
                val trigger = msg.substringAfter("(").substringBefore(")").lowercase().replace(Regex("[^a-z_]"), "_")
                record(BetaCategory.USAGE, "info", "sos_started", null, mapOf("trigger" to trigger, "test_mode" to msg.contains("Test Mode on")))
            }
            tag == "SOS" && (msg.startsWith("Texting ") || msg.startsWith("Location texted")) && sosStartedAt > 0 -> {
                record(BetaCategory.PERFORMANCE, "info", "sos_to_first_text", null, mapOf("ms" to SystemClock.elapsedRealtime() - sosStartedAt))
                sosStartedAt = 0
            }
            tag == "Pocket" && msg.startsWith("Pocket SOS cancelled") -> record(BetaCategory.FALSE_ALARM, "warning", "pocket_sos_cancelled", null)
            tag == "SOS" && msg.startsWith("Auto-call cancelled") -> record(BetaCategory.FALSE_ALARM, "info", "auto_call_cancelled", null)
            msg.contains("fingerprint", ignoreCase = true) && (level == "error" || level == "warn") ->
                record(BetaCategory.SECURITY, "critical", "${slug(tag)}_fingerprint_mismatch", msg)
            usage != null -> record(BetaCategory.USAGE, "info", usage.third, null)
            level == "error" || level == "warn" -> {
                // Places and people can appear in these parts' wording, so only the technical cause is kept.
                val text = if (tag in PLACE_TAGS) Regex("\\((\\w+(?:Exception|Error)[^)]*)\\)").find(msg)?.groupValues?.get(1) else msg
                val first = scrub(if (tag in PLACE_TAGS) msg else text ?: msg).substringBefore(":").substringBefore("(").trim()
                val code = "${slug(tag)}_" + slug(first).split('_').take(if (tag in PLACE_TAGS) 2 else 4).joinToString("_").ifBlank { level }
                record(BetaCategory.ERROR, if (level == "error") "error" else "warning", code, text, mapOf("tag" to tag))
            }
        }
    }

    private val PLACE_TAGS = setOf("Journey", "Location", "Live", "Map", "SMS", "Community", "Account", "Fake call", "Chat")

    private val USAGE_CODES = listOf(
        Triple("Pocket", "Three knocks detected", "pocket_sos_triggered"),
        Triple("Journey", "Started to", "journey_started"),
        Triple("Journey", "She answered the check-in", "journey_checkin_ok"),
        Triple("Journey", "Alerting contacts", "journey_alert_sent"),
        Triple("Fake call", "Incoming call", "fake_call"),
        Triple("Live", "Sharing live location", "live_location_started"),
        Triple("Evidence", "Recording stopped", "evidence_recorded"),
        Triple("Evidence", "Exported", "evidence_exported"),
        Triple("Chat", "Answered", "assistant_question"),
        Triple("Update", "Downloaded", "app_update_downloaded"),
        Triple("Watch install", "Installed", "watch_installed"),
        Triple("Watch install", "Paired", "watch_paired"),
        Triple("Community", "Added", "community_report_added"),
        Triple("Settings", "Safety PIN set", "safety_pin_set"),
        Triple("Account", "Verified", "account_verified"),
    )

    // ---- crashes and startup time

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                if (allowed(BetaCategory.CRASH)) {
                    val frames = e.stackTrace.take(25).joinToString("\n") { "  at $it" }
                    val top = e.stackTrace.firstOrNull { it.className.startsWith("in.security4her") }?.toString() ?: e.stackTrace.firstOrNull()?.toString()
                    record(BetaCategory.CRASH, "critical", "crash_${e.javaClass.simpleName}", "${e.javaClass.name}: ${e.message}\n$frames" +
                        (e.cause?.let { "\nCaused by ${it.javaClass.name}: ${it.message}" } ?: ""),
                        mapOf("thread" to t.name, "top_frame" to top))
                }
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun measureStart(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            var done = false
            override fun onActivityResumed(a: Activity) {
                if (done) return
                done = true
                val ms = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
                if (ms in 1..60_000) record(BetaCategory.PERFORMANCE, "info", "app_start", null, mapOf("ms" to ms))
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // ---- upload

    private var flushing = false

    fun flush() {
        if (!::app.isInitialized) return
        scope.launch {
            synchronized(this@Beta) { if (flushing) return@launch; flushing = true }
            try { upload() } catch (_: Exception) {} finally { flushing = false; refresh() }
        }
    }

    private fun upload() {
        val p = Store.prefs(app)
        if (!p.getBoolean("beta_consent_synced", false) && state.value.decided) {
            val cfg = config() ?: return
            val body = JSONObject().put("p_tester", tester()).put("p_app", APP).put("p_version", CONSENT_VERSION)
                .put("p_categories", JSONArray(state.value.categories.map { it.code }))
                .put("p_key_hash", sha256(p.getString("beta_key", "")!!))
            val (code, _) = post("${cfg.first}/rest/v1/rpc/beta_set_consent", cfg.second, body.toString())
            if (code !in 200..299) return
            p.edit().putBoolean("beta_consent_synced", true).apply()
        }
        if (!state.value.agreed) return
        val cfg = config() ?: return
        while (true) {
            val lines = synchronized(this) { if (queueFile.exists()) queueFile.readLines().filter { it.isNotBlank() } else emptyList() }
            if (lines.isEmpty()) return
            val batch = lines.take(100)
            val (code, _) = post("${cfg.first}/rest/v1/beta_events", cfg.second, batch.joinToString(",", "[", "]"))
            if (code == 429 || code >= 500 || code < 0) return // try again later
            // Sent, or refused for good (bad row or consent changed): either way it leaves the queue.
            synchronized(this) {
                val now = if (queueFile.exists()) queueFile.readLines().filter { it.isNotBlank() } else emptyList()
                queueFile.writeText(now.drop(batch.size).joinToString("\n", postfix = if (now.size > batch.size) "\n" else ""))
            }
            if (code in 200..299) p.edit().putInt("beta_sent", p.getInt("beta_sent", 0) + batch.size).apply()
        }
    }

    /** The test server's address comes from the website's version.json, so it can be set without a new app build. */
    private fun config(): Pair<String, String>? {
        val p = Store.prefs(app)
        // Re-check every 6 h once configured; every 15 min while the test server isn't set up yet.
        val every = if (p.getString("beta_url", "").isNullOrBlank()) 15 * 60_000L else 6 * 3600_000L
        val fresh = System.currentTimeMillis() - p.getLong("beta_cfg_at", 0) < every
        if (!fresh) runCatching {
            val c = (URL(BuildConfig.UPDATE_URL + "?b=" + System.currentTimeMillis()).openConnection() as HttpURLConnection)
                .apply { connectTimeout = 10_000; readTimeout = 10_000; useCaches = false }
            val beta = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONObject("beta")
            p.edit().putString("beta_url", beta?.optString("url").orEmpty().trimEnd('/'))
                .putString("beta_anon", beta?.optString("key").orEmpty()).putLong("beta_cfg_at", System.currentTimeMillis()).apply()
        }
        val url = p.getString("beta_url", "").orEmpty(); val key = p.getString("beta_anon", "").orEmpty()
        return if (url.startsWith("https://") && key.isNotBlank()) url to key else null
    }

    private fun post(url: String, key: String, json: String): Pair<Int, String> = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("apikey", key); setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Prefer", "return=minimal")
        }
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        code to runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
    } catch (e: Exception) { -1 to "" }

    // ---- scrubbing: personal data never leaves the phone

    fun scrub(text: String): String = scrubText(text, if (::app.isInitialized) knownNames() else emptyList())

    /** Pure, so it can be unit-tested. [names] are the user's own and her contacts' names. */
    internal fun scrubText(text: String, names: List<String>): String {
        var s = text
        s = s.replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "[email]")
        s = s.replace(Regex("https?://\\S+"), "[link]")
        s = s.replace(Regex("-?\\d{1,3}\\.\\d{3,}\\s*,\\s*-?\\d{1,3}\\.\\d{3,}"), "[location]")
        s = s.replace(Regex("-?\\d{1,3}\\.\\d{4,}"), "[coord]")
        s = s.replace(Regex("\\+?\\d[\\d\\s-]{6,}\\d"), "[number]")
        s = s.replace(Regex("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b"), "[number]")
        s = s.replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "[ip]")
        for (n in names) if (n.length >= 3) s = s.replace(Regex("\\b" + Regex.escape(n) + "\\b", RegexOption.IGNORE_CASE), "[name]")
        return s
    }

    private fun knownNames(): List<String> = runCatching {
        val p = Store.prefs(app)
        val names = mutableListOf<String>()
        p.all.forEach { (k, v) ->
            if (v is String && (k.contains("contact") || k.contains("profile") || k.contains("caller") || k.contains("journey"))) {
                Regex("\"(?:name|displayName|display_name)\"\\s*:\\s*\"([^\"]+)\"").findAll(v).forEach { m -> names += m.groupValues[1].split(' ') + m.groupValues[1] }
                if (k == "fake_caller") names += v
            }
        }
        names.map { it.trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }
    }.getOrDefault(emptyList())

    // ---- helpers

    private fun deviceInfo(): Map<String, Any?> {
        val dm = app.resources.displayMetrics
        return mapOf("brand" to Build.BRAND, "model" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT, "release" to Build.VERSION.RELEASE,
            "locale" to Locale.getDefault().toLanguageTag(), "screen_dp" to screenDp(), "density" to dm.densityDpi)
    }

    private fun screenDp(): String { val dm = app.resources.displayMetrics; return "${(dm.widthPixels / dm.density).toInt()}x${(dm.heightPixels / dm.density).toInt()}" }

    private fun fingerprint(cat: String, code: String, msg: String?, frame: String?): String {
        val norm = (msg ?: "").lines().first().replace(Regex("\\d+"), "#").take(160)
        return sha256("$cat|$code|${frame ?: norm}").take(24)
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "app" }
    private fun hex(bytes: Int) = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun mapOfNotNull(vararg p: Pair<String, Any?>) = p.filter { it.second != null }.toMap()
}

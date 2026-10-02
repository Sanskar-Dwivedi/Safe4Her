package `in`.security4her.app.core

import android.content.Context
import android.content.SharedPreferences
import `in`.security4her.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Data Layer paths and capabilities. Must match CONTRACT.md §2 exactly. */
object Paths {
    const val CAP_WATCH = "s4h_watch"
    const val SOS_START = "/s4h/sos/start"
    const val SOS_LOCATION = "/s4h/sos/location"
    const val SOS_CANCEL = "/s4h/sos/cancel"
    const val SOS_ACK = "/s4h/sos/ack"
    const val LINK_REQUEST = "/s4h/link/request"
    const val FAKE_CALL_START = "/s4h/fake-call/start"
    const val FAKE_CALL_ACK = "/s4h/fake-call/ack"
    const val STATUS = "/s4h/status"
    const val DEVICE = "/s4h/device"
    const val DATA_KEY_JSON = "json"
}

val S4hJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// ---- Data Layer payloads (CONTRACT.md §2) ----

@Serializable
data class SosStartMsg(
    val incidentId: String,
    val trigger: String,
    val createdAt: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    val fixAt: Long? = null,
)

@Serializable
data class SosLocationMsg(val incidentId: String, val lat: Double, val lng: Double, val accuracyM: Float? = null, val fixAt: Long)

@Serializable
data class IncidentRef(val incidentId: String, val pin: String? = null) // pin: typed on the watch to end an SOS

@Serializable
data class FakeCallRequest(val requestId: String, val createdAt: Long)

@Serializable
data class FakeCallAck(val requestId: String, val accepted: Boolean)

@Serializable
data class PhoneStatus(
    val incidentId: String,
    val state: String,
    val smsSent: Int = 0,
    val smsFailed: Int = 0,
    val serverAccepted: Boolean = false,
    val callPlaced: Boolean = false,
    val shareUrl: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class DeviceLink(
    val userId: String, val displayName: String = "", val deviceToken: String, val testMode: Boolean = true,
    // A salted hash of the safety PIN (never the PIN), so the watch can ask for it before ending an SOS.
    val pinHash: String = "", val pinSalt: String = "", val pinIter: Int = 0,
)

// ---- Supabase rows (CONTRACT.md §3) ----

@Serializable
data class Profile(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val phone: String? = null,
    @SerialName("verified_female") val verifiedFemale: Boolean = false,
    @SerialName("verification_method") val verificationMethod: String? = null,
)

@Serializable
data class Contact(
    val id: String = "",
    val name: String,
    val phone: String,
    val relation: String? = null,
    val priority: Int = 1,
    @SerialName("receives_sos") val receivesSos: Boolean = true,
    @SerialName("telegram_chat_id") val telegramChatId: Long? = null,
    @SerialName("telegram_link_code") val telegramLinkCode: String? = null,
)

@Serializable
private data class IncidentRow(val id: String, @SerialName("share_token") val shareToken: String, val status: String, val created: Boolean)

// ---- Local settings (SharedPreferences) ----

data class Settings(
    val testMode: Boolean,
    val demoNumber: String,
    val policeNumber: String,
    val autoCallNumber: String,
    val countdownSec: Int,
    val pin: String,
    val smsUpdates: Boolean,
    val evidence: Boolean,
    val evidenceVideo: Boolean,
) {
    companion object {
        fun load(ctx: Context): Settings = with(Store.prefs(ctx)) {
            Pin.migrate(ctx)
            if (!getBoolean("police_100", false)) {
                edit().apply { if (getString("police_number", null) == "112") putString("police_number", "100") }.putBoolean("police_100", true).apply()
            }
            Settings(
                testMode = getBoolean("test_mode", true), // on until she turns it off: police texts and the auto-call go to her test number
                demoNumber = getString("demo_number", "")!!,
                policeNumber = getString("police_number", "100")!!,
                autoCallNumber = getString("auto_call_number", "")!!,
                countdownSec = getInt("countdown_sec", 10),
                pin = "", // only a salted hash is kept (Pin); this field carries a NEW PIN to save
                smsUpdates = getBoolean("sms_updates", true),
                evidence = getBoolean("evidence", true),
                evidenceVideo = getBoolean("evidence_video", true),
            )
        }
    }

    fun save(ctx: Context) {
        if (pin.length == 4 && pin.all(Char::isDigit)) Pin.set(ctx, pin)
        saveRest(ctx)
    }

    private fun saveRest(ctx: Context) = Store.prefs(ctx).edit()
        .putBoolean("test_mode", testMode)
        .putString("demo_number", demoNumber.trim())
        .putString("police_number", policeNumber.trim())
        .putString("auto_call_number", autoCallNumber.trim())
        .putInt("countdown_sec", countdownSec.coerceIn(3, 60))
        .putBoolean("sms_updates", smsUpdates)
        .putBoolean("evidence", evidence)
        .putBoolean("evidence_video", evidenceVideo)
        .apply()
}

object Store {
    fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("s4h", Context.MODE_PRIVATE)
}

/**
 * Supabase access. With no SUPABASE_URL in local.properties the app runs in demo mode against local fake data,
 * so the whole flow can be shown without a backend.
 */
object Backend {
    val mock: Boolean = BuildConfig.SUPABASE_URL.isBlank()
    lateinit var client: SupabaseClient
        private set
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        client = createSupabaseClient(
            supabaseUrl = if (mock) "https://demo.supabase.co" else BuildConfig.SUPABASE_URL,
            supabaseKey = if (mock) "demo-anon-key" else BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth)
            install(Postgrest)
        }
    }

    // ---- Auth + profile ----

    suspend fun restore(): Profile? {
        if (mock) return mockProfile()
        val status = client.auth.sessionStatus.first { it !is SessionStatus.Initializing }
        if (status !is SessionStatus.Authenticated) return null
        return profile()
    }

    suspend fun signUp(name: String, email: String, password: String): Profile {
        if (mock) return saveMockProfile(Profile(UUID.randomUUID().toString(), displayName = name.trim())) // MOCK: no Supabase configured
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject { put("display_name", name.trim()) }
        }
        return profile()
    }

    suspend fun signIn(email: String, password: String): Profile {
        if (mock) return mockProfile() ?: saveMockProfile(Profile(UUID.randomUUID().toString(), displayName = email.substringBefore('@')))
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        return profile()
    }

    suspend fun signOut() {
        if (mock) Store.prefs(app).edit().remove("mock_profile").apply() else client.auth.signOut()
    }

    suspend fun profile(): Profile {
        if (mock) return mockProfile() ?: error("Not signed in")
        val uid = client.auth.currentUserOrNull()?.id ?: error("Not signed in")
        return client.from("profiles").select { filter { eq("id", uid) } }.decodeSingle()
    }

    suspend fun mockVerifyFemale(method: String = "mock"): Boolean {
        if (mock) {
            val p = mockProfile() ?: return false
            saveMockProfile(p.copy(verifiedFemale = true, verificationMethod = method))
            return true
        }
        return client.postgrest.rpc("mock_verify_female").decodeAs<Boolean>()
    }

    // ---- Trusted contacts ----

    suspend fun contacts(): List<Contact> {
        if (mock) return mockContacts().sortedBy { it.priority }
        return client.from("trusted_contacts").select { order("priority", Order.ASCENDING) }.decodeList()
    }

    suspend fun saveContact(c: Contact) {
        if (mock) {
            val all = mockContacts().filter { it.id != c.id }
            val saved = if (c.id.isBlank()) {
                c.copy(id = UUID.randomUUID().toString(), telegramLinkCode = UUID.randomUUID().toString().take(8)) // MOCK
            } else c
            saveMockContacts(all + saved)
            return
        }
        val body = buildJsonObject {
            put("name", c.name.trim())
            put("phone", c.phone.trim())
            put("relation", c.relation?.trim()?.ifBlank { null })
            put("priority", c.priority)
            put("receives_sos", c.receivesSos)
        }
        if (c.id.isBlank()) client.from("trusted_contacts").insert(body)
        else client.from("trusted_contacts").update(body) { filter { eq("id", c.id) } }
    }

    suspend fun deleteContact(id: String) {
        if (mock) return saveMockContacts(mockContacts().filter { it.id != id })
        client.from("trusted_contacts").delete { filter { eq("id", id) } }
    }

    // ---- Incidents ----

    /** Returns the share token. Idempotent on [id]. */
    suspend fun upsertIncident(id: String, trigger: String, source: String, lat: Double?, lng: Double?, acc: Float?, test: Boolean): String {
        if (mock) return "demo-" + id.take(8) // MOCK: fake share token, the link page will say "ended"
        return client.postgrest.rpc(
            "upsert_incident",
            buildJsonObject {
                put("p_id", id)
                put("p_trigger", trigger)
                put("p_source", source)
                put("p_lat", lat)
                put("p_lng", lng)
                put("p_acc", acc)
                put("p_test", test)
            },
        ).decodeList<IncidentRow>().first().shareToken
    }

    suspend fun addLocation(id: String, lat: Double, lng: Double, acc: Float?, fixAtIso: String) {
        if (mock) return
        client.postgrest.rpc(
            "add_incident_location",
            buildJsonObject {
                put("p_id", id)
                put("p_lat", lat)
                put("p_lng", lng)
                put("p_acc", acc)
                put("p_fix_at", fixAtIso)
            },
        )
    }

    suspend fun endIncident(id: String, status: String) {
        if (mock) return
        client.postgrest.rpc("end_incident", buildJsonObject { put("p_id", id); put("p_status", status) })
    }

    suspend fun registerWatch(): String {
        if (mock) return "demo-watch-" + (mockProfile()?.id?.take(8) ?: "token") // MOCK
        return client.postgrest.rpc("register_watch").decodeAs<String>()
    }

    // ---- Demo-mode storage ----

    private fun mockProfile(): Profile? =
        Store.prefs(app).getString("mock_profile", null)?.let { runCatching { S4hJson.decodeFromString(Profile.serializer(), it) }.getOrNull() }

    private fun saveMockProfile(p: Profile): Profile {
        Store.prefs(app).edit().putString("mock_profile", S4hJson.encodeToString(Profile.serializer(), p)).apply()
        return p
    }

    private val contactList = ListSerializer(Contact.serializer())

    private fun mockContacts(): List<Contact> =
        Store.prefs(app).getString("mock_contacts", null)?.let { runCatching { S4hJson.decodeFromString(contactList, it) }.getOrNull() }
            ?: emptyList()

    private fun saveMockContacts(list: List<Contact>) {
        Store.prefs(app).edit().putString("mock_contacts", S4hJson.encodeToString(contactList, list)).apply()
    }
}

/** Plain-English text for backend errors. */
fun Throwable.friendly(): String {
    val m = message.orEmpty()
    return when {
        "Invalid login" in m || "invalid_credentials" in m -> "That email and password don't match an account."
        "already registered" in m || "already been registered" in m -> "An account with this email already exists. Sign in instead."
        "Password should be" in m || "weak_password" in m -> "Use a password of at least 6 characters."
        "not verified" in m -> "Verify your account first."
        "Not signed in" in m -> "Sign in again."
        else -> "Couldn't reach the server. Check your connection and try again."
    }
}

// ---- Activity log: every SOS step, text, location fix, live update and error, kept on the phone ----

@Serializable
data class LogEntry(val at: Long, val level: String, val tag: String, val msg: String) // level: info | ok | warn | error

object AppLog {
    private const val MAX = 1500
    private var file: java.io.File? = null
    private val _entries = kotlinx.coroutines.flow.MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: kotlinx.coroutines.flow.StateFlow<List<LogEntry>> = _entries

    fun init(ctx: Context) {
        val f = java.io.File(ctx.filesDir, "activity-log.jsonl")
        file = f
        _entries.value = runCatching {
            f.takeIf { it.exists() }?.readLines()?.takeLast(MAX)?.mapNotNull { line ->
                runCatching { S4hJson.decodeFromString(LogEntry.serializer(), line) }.getOrNull()
            }
        }.getOrNull().orEmpty()
    }

    fun info(tag: String, msg: String) = add("info", tag, msg)
    fun ok(tag: String, msg: String) = add("ok", tag, msg)
    fun warn(tag: String, msg: String, t: Throwable? = null) = add("warn", tag, msg + (t?.let { " (${it.javaClass.simpleName}: ${it.message?.take(120)})" } ?: ""))
    fun error(tag: String, msg: String, t: Throwable? = null) = add("error", tag, msg + (t?.let { " (${it.javaClass.simpleName}: ${it.message?.take(120)})" } ?: ""))

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        val e = LogEntry(System.currentTimeMillis(), level, tag, msg)
        `in`.security4her.app.beta.Beta.fromLog(level, tag, msg)
        android.util.Log.println(if (level == "error") android.util.Log.ERROR else if (level == "warn") android.util.Log.WARN else android.util.Log.INFO, "S4h/$tag", msg)
        val next = _entries.value + e
        _entries.value = if (next.size > MAX) next.takeLast(MAX) else next
        runCatching {
            val f = file ?: return
            f.appendText(S4hJson.encodeToString(LogEntry.serializer(), e) + "\n")
            if (f.length() > 600_000) f.writeText(_entries.value.joinToString("\n", postfix = "\n") { S4hJson.encodeToString(LogEntry.serializer(), it) })
        }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        runCatching { file?.writeText("") }
        info("App", "Log cleared")
    }

    /** Plain-text export for sharing with the team. */
    fun export(list: List<LogEntry> = _entries.value): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return buildString {
            appendLine("Security4Her activity log · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
            list.forEach { appendLine("${fmt.format(java.util.Date(it.at))}  ${it.level.uppercase().padEnd(5)}  [${it.tag}] ${it.msg}") }
        }
    }
}


/**
 * The safety PIN. Stored only as a salted PBKDF2 hash, never as the digits. After 5 wrong tries it locks for 30 s,
 * doubling each further miss (up to an hour), so it can't be guessed by going through all 10,000 PINs.
 */
object Pin {
    sealed interface Check {
        data object Ok : Check
        data class Wrong(val left: Int) : Check
        data class Locked(val seconds: Int) : Check
    }

    private const val ITER = 20_000
    private const val FREE_TRIES = 5

    fun isSet(ctx: Context) = Store.prefs(ctx).contains("pin_hash")

    fun hash(pin: String, salt: ByteArray, iter: Int): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iter, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun set(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        Store.prefs(ctx).edit().putString("pin_hash", hex(hash(pin, salt, ITER))).putString("pin_salt", hex(salt)).putInt("pin_iter", ITER)
            .remove("pin").putInt("pin_fails", 0).remove("pin_locked_until").apply()
        relinkWatch(ctx)
    }

    /** Earlier versions stored the digits themselves: hash them once and delete the plain copy. */
    fun migrate(ctx: Context) {
        val p = Store.prefs(ctx)
        val old = p.getString("pin", null) ?: return
        if (old.length == 4 && !isSet(ctx)) set(ctx, old) else p.edit().remove("pin").apply()
    }

    fun check(ctx: Context, entered: String): Check {
        val p = Store.prefs(ctx)
        val now = System.currentTimeMillis()
        val until = p.getLong("pin_locked_until", 0)
        if (now < until) return Check.Locked(((until - now) / 1000).toInt() + 1)
        val stored = p.getString("pin_hash", null) ?: return Check.Ok
        val ok = runCatching {
            java.security.MessageDigest.isEqual(hash(entered, unhex(p.getString("pin_salt", "")!!), p.getInt("pin_iter", ITER)), unhex(stored))
        }.getOrDefault(false)
        if (ok) { p.edit().putInt("pin_fails", 0).remove("pin_locked_until").apply(); return Check.Ok }
        val fails = p.getInt("pin_fails", 0) + 1
        `in`.security4her.app.beta.Beta.record(`in`.security4her.app.beta.BetaCategory.SECURITY, "warning", "sos_pin_wrong", null, mapOf("fails" to fails))
        if (fails < FREE_TRIES) { p.edit().putInt("pin_fails", fails).apply(); return Check.Wrong(FREE_TRIES - fails) }
        val lockMs = (30_000L shl (fails - FREE_TRIES).coerceAtMost(7)).coerceAtMost(3_600_000L)
        p.edit().putInt("pin_fails", fails).putLong("pin_locked_until", now + lockMs).apply()
        AppLog.warn("Security", "Safety PIN entered wrongly $fails times; locked for ${lockMs / 1000} s")
        return Check.Locked((lockMs / 1000).toInt())
    }

    fun message(c: Check) = when (c) {
        Check.Ok -> ""
        is Check.Wrong -> if (c.left == 1) "That PIN doesn't match. One more wrong try locks it for 30 seconds." else "That PIN doesn't match."
        is Check.Locked -> "Too many wrong tries. Try again in ${if (c.seconds >= 120) "${c.seconds / 60} minutes" else "${c.seconds} seconds"}."
    }

    /** What the watch needs to check the PIN itself: the hash and salt, never the digits. */
    fun forWatch(ctx: Context): Triple<String, String, Int> {
        val p = Store.prefs(ctx)
        return Triple(p.getString("pin_hash", "")!!, p.getString("pin_salt", "")!!, p.getInt("pin_iter", 0))
    }

    private fun relinkWatch(ctx: Context) {
        val app = ctx.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { `in`.security4her.app.sos.WearBridge.linkWatch(app) } }
    }
}

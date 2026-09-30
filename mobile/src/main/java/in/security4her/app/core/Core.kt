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
data class IncidentRef(val incidentId: String)

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
data class DeviceLink(val userId: String, val displayName: String = "", val deviceToken: String, val testMode: Boolean = true)

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
            Settings(
                testMode = getBoolean("test_mode", true), // on until she turns it off: police texts and the auto-call go to her test number
                demoNumber = getString("demo_number", "")!!,
                policeNumber = getString("police_number", "112")!!,
                autoCallNumber = getString("auto_call_number", "")!!,
                countdownSec = getInt("countdown_sec", 10),
                pin = getString("pin", "")!!,
                smsUpdates = getBoolean("sms_updates", true),
                evidence = getBoolean("evidence", true),
                evidenceVideo = getBoolean("evidence_video", true),
            )
        }
    }

    fun save(ctx: Context) = Store.prefs(ctx).edit()
        .putBoolean("test_mode", testMode)
        .putString("demo_number", demoNumber.trim())
        .putString("police_number", policeNumber.trim())
        .putString("auto_call_number", autoCallNumber.trim())
        .putInt("countdown_sec", countdownSec.coerceIn(3, 60))
        .putString("pin", pin)
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

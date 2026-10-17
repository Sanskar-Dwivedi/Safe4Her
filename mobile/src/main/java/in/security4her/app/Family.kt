package `in`.security4her.app

import android.content.Context
import android.util.Base64
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The family link: a child's phone and their grown-ups' phones, connected through one private link
 * (security4her.vercel.app/family.html#secret). The page shows the child's SOS, calls, check-ins and location, and lets
 * a grown-up stop a false alarm.
 *
 * Everything goes through ntfy.sh (the relay the live map already uses), but ntfy only ever sees ciphertext: status
 * updates are AES-256-GCM encrypted with a key derived from the secret, and a "stop" command counts only if it carries
 * an HMAC made with the secret. The secret lives in the link's #fragment, which browsers never send to any server.
 */
object Family {
    private const val PAGE = "https://security4her.vercel.app/family.html"
    private const val RELAY = "https://ntfy.sh"

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun secret(ctx: Context): ByteArray {
        val p = Store.prefs(ctx)
        p.getString("family_secret", null)?.let { return unb64(it) }
        val s = ByteArray(32).also { SecureRandom().nextBytes(it) }
        p.edit().putString("family_secret", b64(s)).apply()
        return s
    }

    /** A new link; the old one stops working at once, and everyone has to connect again. */
    fun reset(ctx: Context) {
        Store.prefs(ctx).edit().remove("family_secret").remove("family_members").remove("family_polled").apply()
        secret(ctx)
        AppLog.info("Family", "Family link replaced; the old link no longer works")
    }

    fun link(ctx: Context) = "$PAGE#" + b64(secret(ctx))

    internal fun statusTopic(s: ByteArray) = "s4hf-" + hex(sha(s, "status".toByteArray())).take(24)
    internal fun commandTopic(s: ByteArray) = "s4hc-" + hex(sha(s, "command".toByteArray())).take(24)

    private fun seal(s: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sha(s, "key".toByteArray()), "AES"), GCMParameterSpec(128, iv))
        return "v1." + b64(iv + c.doFinal(plain.toByteArray()))
    }

    internal fun mac(s: ByteArray, text: String): String =
        b64(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(s, "HmacSHA256")) }.doFinal(text.toByteArray()))

    /** Tells the grown-ups' family page what happened. kind: sos | call | loc | checkin | safe */
    suspend fun post(ctx: Context, kind: String, text: String, lat: Double? = null, lng: Double? = null): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val s = secret(ctx)
            val name = Store.prefs(ctx).getString("family_name", null) ?: "Your child"
            val json = JSONObject().put("k", kind).put("t", text).put("n", name).put("at", System.currentTimeMillis())
            if (lat != null && lng != null) json.put("lat", lat).put("lng", lng)
            val conn = URL("$RELAY/${statusTopic(s)}").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 10_000
            conn.outputStream.use { it.write(seal(s, json.toString()).toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    data class Command(val cmd: String, val by: String, val at: Long)

    /** Signed commands from the family page since [sinceMs]; anything without a valid signature is ignored. */
    suspend fun commands(ctx: Context, sinceMs: Long): List<Command> = withContext(Dispatchers.IO) {
        runCatching {
            val s = secret(ctx)
            val conn = URL("$RELAY/${commandTopic(s)}/json?poll=1&since=${sinceMs / 1000}").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            val lines = conn.inputStream.bufferedReader().readLines()
            conn.disconnect()
            lines.mapNotNull { line ->
                runCatching {
                    val ev = JSONObject(line)
                    if (ev.optString("event") != "message") return@runCatching null
                    val m = JSONObject(ev.getString("message"))
                    val c = Command(m.getString("cmd"), m.optString("by", "A grown-up").take(40), m.getLong("at"))
                    val good = MessageDigest.isEqual(mac(s, "${c.cmd}|${c.at}|${c.by}").toByteArray(), m.getString("mac").toByteArray())
                    val fresh = c.at in (sinceMs - 60_000)..(System.currentTimeMillis() + 60_000)
                    if (good && fresh) c else null
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /** A grown-up whose family page said hello, and when it was last open. */
    data class Member(val name: String, val seen: Long)

    fun members(ctx: Context): List<Member> = runCatching {
        val arr = org.json.JSONArray(Store.prefs(ctx).getString("family_members", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Member(o.getString("n"), o.getLong("s")) } }
    }.getOrDefault(emptyList()).sortedByDescending { it.seen }

    /** Picks up signed hellos from family pages opened since the last look. */
    suspend fun refresh(ctx: Context): List<Member> {
        val p = Store.prefs(ctx)
        val now = System.currentTimeMillis()
        val since = maxOf(p.getLong("family_polled", 0), now - 12 * 3_600_000L)
        val hellos = commands(ctx, since).filter { it.cmd == "hello" }
        if (hellos.isEmpty()) return members(ctx)
        val byName = members(ctx).associateBy { it.name.lowercase() }.toMutableMap()
        hellos.forEach { h ->
            val key = h.by.lowercase()
            val was = byName[key]
            if (was == null) AppLog.ok("Family", "${h.by} connected to the family page")
            if (was == null || h.at > was.seen) byName[key] = Member(h.by, h.at)
        }
        val list = byName.values.sortedByDescending { it.seen }
        val arr = org.json.JSONArray().apply { list.forEach { put(JSONObject().put("n", it.name).put("s", it.seen)) } }
        p.edit().putString("family_members", arr.toString()).putLong("family_polled", now - 60_000).apply()
        return list
    }

    // ---- The grown-up's side: following a child from their own Security4Her ----

    /** A family link opened from a text (App Link), waiting for the parent's app to connect it. */
    val pendingLink = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** The child's key from a family link (or just its #fragment), or null if it isn't one. */
    fun parse(link: String): ByteArray? = runCatching {
        val frag = link.trim().substringAfter('#', link.trim())
        unb64(frag).takeIf { it.size == 32 }
    }.getOrNull()

    fun keyOf(s: ByteArray) = b64(s)
    fun keyFrom(k: String) = unb64(k)

    data class Event(val id: String, val kind: String, val text: String, val name: String, val at: Long, val lat: Double?, val lng: Double?)

    private fun open(s: ByteArray, id: String, message: String): Event? = runCatching {
        if (!message.startsWith("v1.")) return null
        val b = unb64(message.removePrefix("v1."))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(sha(s, "key".toByteArray()), "AES"), GCMParameterSpec(128, b, 0, 12))
        val o = JSONObject(String(c.doFinal(b, 12, b.size - 12)))
        Event(
            id, o.optString("k"), o.optString("t"), o.optString("n", "Your child"), o.optLong("at"),
            if (o.has("lat")) o.getDouble("lat") else null, if (o.has("lng")) o.getDouble("lng") else null,
        )
    }.getOrNull() // not from this child, or tampered with

    /** What the child's phone posted in the last [since] (ntfy keeps 12 hours). */
    suspend fun history(s: ByteArray, since: String = "12h"): List<Event> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$RELAY/${statusTopic(s)}/json?poll=1&since=$since").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            val lines = conn.inputStream.bufferedReader().readLines()
            conn.disconnect()
            lines.mapNotNull { line ->
                runCatching { JSONObject(line) }.getOrNull()?.takeIf { it.optString("event") == "message" }
                    ?.let { open(s, it.getString("id"), it.getString("message")) }
            }.sortedBy { it.at }
        }.getOrDefault(emptyList())
    }

    /** Live: every new event from the child's phone as it's posted, reconnecting by itself. */
    fun live(s: ByteArray): kotlinx.coroutines.flow.Flow<Event> = kotlinx.coroutines.flow.flow {
        while (true) {
            runCatching {
                val conn = URL("$RELAY/${statusTopic(s)}/json").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 90_000 // ntfy sends a keepalive every 45 s
                try {
                    conn.inputStream.bufferedReader().use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (o.optString("event") != "message") continue
                            open(s, o.getString("id"), o.getString("message"))?.let { emit(it) }
                        }
                    }
                } finally { conn.disconnect() }
            }
            kotlinx.coroutines.delay(3_000)
        }
    }.flowOn(Dispatchers.IO)

    /** A signed command to the child's phone: "hello" (connected as [by]) or "stop" (end a false alarm). */
    suspend fun send(s: ByteArray, cmd: String, by: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val at = System.currentTimeMillis()
            val body = JSONObject().put("cmd", cmd).put("by", by).put("at", at).put("mac", mac(s, "$cmd|$at|$by")).toString()
            val conn = URL("$RELAY/${commandTopic(s)}").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 10_000
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }
}

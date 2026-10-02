package `in`.security4her.app.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Something a woman put on the map: a danger zone, a safe area, or a safe place to go to. */
@Serializable
data class CommunityReport(
    val id: String,
    val kind: String, // danger | safe_area | safe_place
    val lat: Double,
    val lng: Double,
    val radiusM: Int = 0,
    val category: String,
    val name: String? = null,
    val note: String? = null,
    val at: Long,
    val by: String,
    val sample: Boolean = false,
)

/** Where an SOS was raised: rounded to about 100 m and anonymous. Feeds the automatic SOS hotspots. */
@Serializable
data class SosPing(val id: String, val lat: Double, val lng: Double, val at: Long, val test: Boolean = false, val sample: Boolean = false)

@Serializable
data class Confirm(val reportId: String, val by: String, val at: Long)

@Serializable
data class CommunityData(
    val reports: List<CommunityReport> = emptyList(),
    val sos: List<SosPing> = emptyList(),
    val confirms: List<Confirm> = emptyList(),
    val lastSync: Long = 0,
)

@Serializable
private data class CommunityEvent(val t: String, val report: CommunityReport? = null, val sos: SosPing? = null, val confirm: Confirm? = null)

/** An area the system marked red because SOS alerts keep being raised there. */
data class Hotspot(val lat: Double, val lng: Double, val count: Int, val lastAt: Long, val radiusM: Int = 300)

data class Category(val key: String, val label: String)

/**
 * Community safety map. Reports and anonymous SOS locations are shared through a public ntfy.sh topic
 * (no account needed) and kept on every phone that has seen them. ntfy only holds messages for 12 hours,
 * so a phone must open the map within that window to receive a report; with the Supabase backend configured
 * this should move to a table.
 */
object Community {
    private const val TOPIC = "s4h-community-v1"
    private const val HOTSPOT_MIN = 2
    private const val HOTSPOT_DAYS = 60
    private const val CELL_DEG = 0.003 // ~330 m cells for SOS hotspots

    val dangerCategories = listOf(
        Category("poor_lighting", "Poor lighting"), Category("harassment", "Harassment"), Category("stalking", "Followed / stalking"),
        Category("isolated", "Isolated, no one around"), Category("drunk", "Drunk crowds"), Category("other", "Other"),
    )
    val safeAreaCategories = listOf(
        Category("busy", "Busy, people around"), Category("well_lit", "Well lit"), Category("police", "Police patrols"),
        Category("cctv", "CCTV cameras"), Category("other", "Other"),
    )
    val safePlaceCategories = listOf(
        Category("shop", "Shop open late"), Category("cafe", "Café or restaurant"), Category("pharmacy", "24-hour pharmacy"),
        Category("hotel", "Hotel reception"), Category("worship", "Place of worship"), Category("other", "Other"),
    )

    fun categories(kind: String) = when (kind) { "danger" -> dangerCategories; "safe_area" -> safeAreaCategories; else -> safePlaceCategories }
    fun label(kind: String, key: String) = categories(kind).firstOrNull { it.key == key }?.label ?: key

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _data = MutableStateFlow(CommunityData())
    val data: StateFlow<CommunityData> = _data.asStateFlow()
    private lateinit var app: Context
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Anonymous per-install id, so the same person can't confirm her own report or confirm twice. */
    fun me(ctx: Context): String = Store.prefs(ctx).getString("community_id", null)
        ?: UUID.randomUUID().toString().also { Store.prefs(ctx).edit().putString("community_id", it).apply() }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val saved = Store.prefs(app).getString("community", null)?.let { runCatching { json.decodeFromString(CommunityData.serializer(), it) }.getOrNull() }
        _data.value = saved ?: samples().also { save(it) }
    }

    private fun save(d: CommunityData) {
        _data.value = d
        Store.prefs(app).edit().putString("community", json.encodeToString(CommunityData.serializer(), d)).apply()
    }

    @Synchronized
    private fun merge(reports: List<CommunityReport> = emptyList(), sos: List<SosPing> = emptyList(), confirms: List<Confirm> = emptyList(), synced: Boolean = false): Int {
        val d = _data.value
        val r = (d.reports + reports).distinctBy { it.id }
        val s = (d.sos + sos).distinctBy { it.id }
        val c = (d.confirms + confirms).distinctBy { it.reportId + it.by }
        val added = (r.size - d.reports.size) + (s.size - d.sos.size) + (c.size - d.confirms.size)
        save(CommunityData(r, s, c, if (synced) System.currentTimeMillis() else d.lastSync))
        return added
    }

    // ---- sharing over ntfy.sh ----

    private fun publish(e: CommunityEvent): Boolean = try {
        val body = buildJsonObject {
            put("topic", TOPIC)
            put("message", json.encodeToString(CommunityEvent.serializer(), e))
            put("priority", 1)
        }.toString()
        val c = (URL("https://ntfy.sh").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (e: Exception) {
        AppLog.warn("Community", "Couldn't share with other users right now", e)
        false
    }

    /** Pulls everything shared in the last 12 hours and keeps it. Returns how many new items arrived. */
    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        try {
            val c = (URL("https://ntfy.sh/$TOPIC/json?poll=1&since=all").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
            }
            val lines = c.inputStream.bufferedReader().use { it.readLines() }.filter { it.length <= 4_096 } // nothing legitimate is bigger
            val events = lines.mapNotNull { line ->
                runCatching {
                    val msg = Json.parseToJsonElement(line).jsonObject["message"]?.jsonPrimitive?.content ?: return@runCatching null
                    json.decodeFromString(CommunityEvent.serializer(), msg)
                }.getOrNull()
            }
            // The feed is public, so anyone can post to it: keep only well-formed, recent items, and at most 10 reports per author.
            val now = System.currentTimeMillis()
            val reports = events.mapNotNull { it.report }.filter { valid(it, now) }
                .groupBy { it.by }.flatMap { (_, l) -> l.sortedByDescending { it.at }.take(10) }
            val sos = events.mapNotNull { it.sos }.filter { !it.test && validPlace(it.lat, it.lng) && validTime(it.at, now) && it.id.length <= 64 }
            val confirms = events.mapNotNull { it.confirm }.filter { it.reportId.length <= 64 && it.by.length in 1..64 && validTime(it.at, now) }
            val dropped = events.size - reports.size - sos.size - confirms.size
            if (dropped > 0) AppLog.info("Community", "Ignored $dropped malformed, old or excess items from the public feed")
            val added = merge(reports = reports, sos = sos, confirms = confirms, synced = true)
            if (added > 0) AppLog.info("Community", "$added new ${if (added == 1) "update" else "updates"} from other users")
            added
        } catch (e: Exception) {
            AppLog.warn("Community", "Couldn't fetch community reports", e)
            0
        }
    }

    // ---- what users do ----

    suspend fun add(ctx: Context, kind: String, lat: Double, lng: Double, radiusM: Int, category: String, name: String?, note: String?): Boolean =
        withContext(Dispatchers.IO) {
            val r = CommunityReport(
                id = UUID.randomUUID().toString(), kind = kind, lat = lat, lng = lng, radiusM = radiusM, category = category,
                name = name?.trim()?.ifBlank { null }, note = note?.trim()?.ifBlank { null }, at = System.currentTimeMillis(), by = me(ctx),
            )
            merge(reports = listOf(r))
            val shared = publish(CommunityEvent("report", report = r))
            AppLog.ok("Community", "Added ${kindLabel(kind).lowercase()}: ${label(kind, category)}" + if (shared) ", shared with others" else ", saved on this phone (not shared yet)")
            if (!shared) queue(CommunityEvent("report", report = r))
            shared
        }

    suspend fun confirm(ctx: Context, report: CommunityReport): Boolean = withContext(Dispatchers.IO) {
        val c = Confirm(report.id, me(ctx), System.currentTimeMillis())
        merge(confirms = listOf(c))
        val shared = publish(CommunityEvent("confirm", confirm = c))
        if (!shared) queue(CommunityEvent("confirm", confirm = c))
        AppLog.ok("Community", "Confirmed: ${label(report.kind, report.category)}")
        shared
    }

    /**
     * Called once per SOS with its first fix. Rounded to ~100 m and to the hour, with no name or number, so the public
     * feed can't be used to work out who raised it. Test SOSs stay on this phone.
     */
    fun recordSos(lat: Double, lng: Double, test: Boolean) {
        val hour = System.currentTimeMillis() / 3_600_000L * 3_600_000L
        val p = SosPing(UUID.randomUUID().toString(), round3(lat), round3(lng), hour, test)
        merge(sos = listOf(p))
        if (test) return
        scope.launch { if (!publish(CommunityEvent("sos", sos = p))) queue(CommunityEvent("sos", sos = p)) }
    }

    private val kinds = setOf("danger", "safe_area", "safe_place")
    private fun validPlace(lat: Double, lng: Double) = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
    private fun validTime(at: Long, now: Long) = at in (now - 30L * 86_400_000)..(now + 5 * 60_000)
    private fun valid(r: CommunityReport, now: Long) =
        r.kind in kinds && validPlace(r.lat, r.lng) && validTime(r.at, now) && r.radiusM in 0..2_000 &&
            r.id.length in 1..64 && r.by.length in 1..64 && Regex("[a-z_]{1,24}").matches(r.category) &&
            (r.name?.length ?: 0) <= 60 && (r.note?.length ?: 0) <= 200 && !r.sample

    // Items that couldn't be shared (no internet) are retried on the next sync.
    private fun queue(e: CommunityEvent) {
        val q = Store.prefs(app).getStringSet("community_queue", emptySet())!!.toMutableSet()
        q += json.encodeToString(CommunityEvent.serializer(), e)
        Store.prefs(app).edit().putStringSet("community_queue", q).apply()
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        val q = Store.prefs(app).getStringSet("community_queue", emptySet())!!
        if (q.isEmpty()) return@withContext
        val left = q.filterNot { s -> runCatching { publish(json.decodeFromString(CommunityEvent.serializer(), s)) }.getOrDefault(false) }.toSet()
        Store.prefs(app).edit().putStringSet("community_queue", left).apply()
        if (left.size < q.size) AppLog.ok("Community", "Shared ${q.size - left.size} saved reports")
    }

    // ---- reading ----

    /** How many different people stand behind a report (its author plus confirmations). */
    fun people(r: CommunityReport, d: CommunityData = _data.value) = 1 + d.confirms.filter { it.reportId == r.id && it.by != r.by }.map { it.by }.distinct().size

    fun confirmedByMe(ctx: Context, r: CommunityReport) = r.by == me(ctx) || _data.value.confirms.any { it.reportId == r.id && it.by == me(ctx) }

    /** SOS alerts grouped into ~330 m cells; a cell with 2+ alerts in 60 days becomes a red hotspot. */
    fun hotspots(d: CommunityData = _data.value): List<Hotspot> {
        val since = System.currentTimeMillis() - HOTSPOT_DAYS * 86_400_000L
        return d.sos.filter { it.at >= since }
            .groupBy { floor(it.lat / CELL_DEG).toLong() to floor(it.lng / CELL_DEG).toLong() }
            .values.filter { it.size >= HOTSPOT_MIN }
            .map { g -> Hotspot(g.map { it.lat }.average(), g.map { it.lng }.average(), g.size, g.maxOf { it.at }) }
    }

    /** The danger zone or hotspot she's standing in, if any. */
    fun dangerAt(lat: Double, lng: Double, d: CommunityData = _data.value): Pair<CommunityReport?, Hotspot?>? {
        val r = d.reports.filter { it.kind == "danger" && meters(lat, lng, it.lat, it.lng) <= it.radiusM }.maxByOrNull { people(it, d) }
        val h = hotspots(d).firstOrNull { meters(lat, lng, it.lat, it.lng) <= it.radiusM }
        return if (r == null && h == null) null else r to h
    }

    fun kindLabel(kind: String) = when (kind) { "danger" -> "Danger zone"; "safe_area" -> "Safe area"; else -> "Safe place" }

    fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * 6_371_000.0 * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun round3(v: Double) = String.format(Locale.US, "%.3f", v).toDouble()

    // MOCK: sample community data for the demo city (Nagpur) so the map isn't empty on first launch.
    // Every item is marked sample = true and labelled "Sample" in the app. Names are generic on purpose.
    private fun samples(): CommunityData {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        fun r(id: String, kind: String, lat: Double, lng: Double, radius: Int, cat: String, name: String?, note: String, ago: Int) =
            CommunityReport("sample-$id", kind, lat, lng, radius, cat, name, note, now - ago * day, "sample-$id-author", sample = true)
        val reports = listOf(
            r("d1", "danger", 21.1492, 79.0852, 200, "poor_lighting", null, "Street lights out along the fort wall after 8 pm.", 3),
            r("d2", "danger", 21.1518, 79.0903, 150, "harassment", null, "Men hanging around the auto stand near the station exit at night.", 6),
            r("d3", "danger", 21.1405, 79.0690, 150, "stalking", null, "Was followed from the bus stop to the main road.", 11),
            r("d4", "danger", 21.1325, 79.0985, 250, "isolated", null, "Empty stretch, shops close by 9 pm.", 15),
            r("a1", "safe_area", 21.1437, 79.0850, 250, "busy", null, "Crowded market, shops open till 10 pm.", 4),
            r("a2", "safe_area", 21.1450, 79.0800, 150, "police", null, "Traffic police booth, staffed most evenings.", 9),
            r("p1", "safe_place", 21.1462, 79.0842, 0, "pharmacy", "24-hour medical store, Sitabuldi", "Open all night. Staff let you wait inside.", 2),
            r("p2", "safe_place", 21.1398, 79.0712, 0, "cafe", "Late-night café, Dharampeth", "Open till midnight, well lit, staff are helpful.", 8),
            r("p3", "safe_place", 21.1375, 79.0822, 0, "hotel", "Hotel reception (24h), Ramdaspeth", "Security guard at the gate all night.", 12),
            r("p4", "safe_place", 21.1560, 79.0790, 0, "worship", "Gurudwara, Sadar", "Always open, people around at any hour.", 20),
        )
        val confirms = listOf("d1" to 3, "d2" to 2, "a1" to 4, "p1" to 5, "p2" to 2).flatMap { (id, n) ->
            (1..n).map { Confirm("sample-$id", "sample-voter-$id-$it", now - it * day) }
        }
        val sos = listOf(
            21.1521 to 79.0907, 21.1519 to 79.0901, 21.1524 to 79.0909, // near the station: becomes a hotspot
            21.1330 to 79.0990, 21.1334 to 79.0981,
        ).mapIndexed { i, (a, b) -> SosPing("sample-sos-$i", a, b, now - (i * 5 + 1) * day, sample = true) }
        return CommunityData(reports, sos, confirms)
    }
}

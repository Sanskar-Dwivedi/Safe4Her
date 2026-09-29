package `in`.security4her.app.map

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Map data (CONTRACT.md §5). Talks only to the map RPCs in CONTRACT.md §3.

@Serializable
data class SafePlace(
    val id: Long,
    val name: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    @SerialName("distance_m") val distanceM: Double,
    val phone: String? = null,
    val verified: Boolean = false,
)

@Serializable
data class DangerCell(
    val lat: Double,
    val lng: Double,
    val score: Double,
    val reports: Int,
)

enum class ReportCategory(val key: String, val label: String) {
    POOR_LIGHTING("poor_lighting", "Poor lighting"),
    HARASSMENT("harassment", "Harassment"),
    STALKING("stalking", "Stalking / followed"),
    ISOLATED("isolated", "Isolated / deserted"),
    OTHER("other", "Other"),
}

class MapRepository(private val supabase: SupabaseClient) {

    suspend fun nearestSafePlaces(lat: Double, lng: Double, n: Int = 30): List<SafePlace> =
        supabase.postgrest.rpc(
            "nearest_safe_places",
            buildJsonObject {
                put("p_lat", lat)
                put("p_lng", lng)
                put("p_n", n)
            },
        ).decodeList<SafePlace>()

    suspend fun dangerCells(minLat: Double, minLng: Double, maxLat: Double, maxLng: Double): List<DangerCell> =
        supabase.postgrest.rpc(
            "danger_cells_in_bbox",
            buildJsonObject {
                put("p_min_lat", minLat)
                put("p_min_lng", minLng)
                put("p_max_lat", maxLat)
                put("p_max_lng", maxLng)
            },
        ).decodeList<DangerCell>()

    suspend fun reportUnsafe(lat: Double, lng: Double, category: ReportCategory, note: String?) {
        supabase.postgrest.rpc(
            "report_unsafe",
            buildJsonObject {
                put("p_lat", lat)
                put("p_lng", lng)
                put("p_category", category.key)
                put("p_note", note?.takeIf { it.isNotBlank() })
            },
        )
    }
}

/** Human-friendly error text for RPC failures (the DB raises plain-English messages). */
internal fun Throwable.userMessage(): String {
    val m = message.orEmpty()
    return when {
        "not verified" in m -> "Verify your account to use the safety map."
        "report limit" in m -> "You've reached today's report limit. Thank you for helping!"
        "invalid coordinates" in m -> "That location isn't valid."
        else -> "Couldn't reach the server. Check your connection and try again."
    }
}

internal fun formatDistance(m: Double): String =
    if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(java.util.Locale.US, "%.1f km", m / 1000)

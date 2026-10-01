package `in`.security4her.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Data Layer paths and capabilities. Must match CONTRACT.md §2 exactly. */
object Paths {
    const val CAP_PHONE = "s4h_phone"
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
data class SosLocationMsg(
    val incidentId: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val fixAt: Long,
)

@Serializable
data class IncidentRef(val incidentId: String)

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
    val userId: String,
    val displayName: String = "",
    val deviceToken: String,
    val testMode: Boolean = true,
)

// ---- watch-sos Edge Function (CONTRACT.md §3) ----

@Serializable
data class WatchSosRequest(
    val action: String,
    val incidentId: String,
    val trigger: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null,
    val fixAt: Long? = null,
    val test: Boolean = true,
)

@Serializable
data class WatchSosResponse(
    val ok: Boolean = false,
    val created: Boolean = false,
    val status: String? = null,
    val shareUrl: String? = null,
    val telegramSent: Int = 0,
    val error: String? = null,
)

// ---- Local state ----

@Serializable
data class Fix(val lat: Double, val lng: Double, val accuracyM: Float? = null, val at: Long)

/** The one incident this watch knows about. Persisted before any transport is attempted. */
@Serializable
data class ActiveIncident(
    val incidentId: String,
    val trigger: String,
    val createdAt: Long,
    val test: Boolean = true,
    val fix: Fix? = null,
    val phoneNodes: Int = 0,
    val phoneAck: Boolean = false,
    val phoneAttempts: Int = 0,
    val serverAccepted: Boolean = false,
    val serverError: String? = null,
    val shareUrl: String? = null,
    val telegramSent: Int = 0,
    val ended: Boolean = false,
    val endedAt: Long? = null,
) {
    val delivered: Boolean get() = phoneAck || serverAccepted

    fun startMsg() = SosStartMsg(incidentId, trigger, createdAt, fix?.lat, fix?.lng, fix?.accuracyM, fix?.at)

    fun serverRequest(action: String, f: Fix? = fix) = WatchSosRequest(
        action = action,
        incidentId = incidentId,
        trigger = trigger,
        lat = f?.lat,
        lng = f?.lng,
        accuracyM = f?.accuracyM,
        fixAt = f?.at,
        test = test,
    )
}

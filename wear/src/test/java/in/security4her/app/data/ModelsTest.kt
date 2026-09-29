package `in`.security4her.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the wire format in CONTRACT.md §2/§3 — the phone and server depend on these exact keys. */
class ModelsTest {
    private fun keys(json: String): Set<String> = (Json.parseToJsonElement(json) as JsonObject).keys

    @Test fun startMessageUsesContractKeysAndOmitsMissingFix() {
        val incident = ActiveIncident(incidentId = "id-1", trigger = "knock", createdAt = 5)
        val json = S4hJson.encodeToString(SosStartMsg.serializer(), incident.startMsg())
        assertEquals(setOf("incidentId", "trigger", "createdAt"), keys(json))
        val withFix = incident.copy(fix = Fix(28.6, 77.2, 9.5f, 7))
        val json2 = S4hJson.encodeToString(SosStartMsg.serializer(), withFix.startMsg())
        assertEquals(setOf("incidentId", "trigger", "createdAt", "lat", "lng", "accuracyM", "fixAt"), keys(json2))
    }

    @Test fun phoneStatusParsesWithUnknownAndMissingFields() {
        val s = S4hJson.decodeFromString(PhoneStatus.serializer(),
            """{"incidentId":"a","state":"active","smsSent":2,"futureField":true}""")
        assertEquals(2, s.smsSent)
        assertFalse(s.serverAccepted)
        assertNull(s.shareUrl)
    }

    @Test fun deviceLinkDefaultsToTestMode() {
        val l = S4hJson.decodeFromString(DeviceLink.serializer(), """{"userId":"u","deviceToken":"t"}""")
        assertTrue(l.testMode)
    }

    @Test fun serverRequestMatchesWatchSosContract() {
        val incident = ActiveIncident("id-2", "shake", 1, test = false, fix = Fix(1.0, 2.0, null, 3))
        val obj = Json.parseToJsonElement(
            S4hJson.encodeToString(WatchSosRequest.serializer(), incident.serverRequest("start"))).jsonObject
        assertEquals(setOf("action", "incidentId", "trigger", "lat", "lng", "fixAt", "test"), obj.keys)
        assertEquals("false", obj["test"].toString())
    }
}

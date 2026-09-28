package `in`.security4her.app.service

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import `in`.security4her.app.data.DeviceLink
import `in`.security4her.app.data.IncidentRef
import `in`.security4her.app.data.Paths
import `in`.security4her.app.data.PhoneStatus
import `in`.security4her.app.data.S4hJson
import `in`.security4her.app.data.WatchStore
import `in`.security4her.app.sos.StatusSync
import kotlinx.coroutines.runBlocking

/** Receives phone → watch traffic (CONTRACT.md §2). Callbacks run on a background thread. */
class WatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Paths.SOS_ACK) return
        val ref = decode(String(event.data, Charsets.UTF_8)) { S4hJson.decodeFromString(IncidentRef.serializer(), it) } ?: return
        runBlocking {
            WatchStore.get(this@WatchListenerService).updateIncident(ref.incidentId) { it.copy(phoneAck = true) }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        // Copy what we need before the buffer is released.
        val changes = events.filter { it.type == DataEvent.TYPE_CHANGED }.mapNotNull { e ->
            val path = e.dataItem.uri.path ?: return@mapNotNull null
            val json = DataMapItem.fromDataItem(e.dataItem).dataMap.getString(Paths.DATA_KEY_JSON) ?: return@mapNotNull null
            path to json
        }
        val store = WatchStore.get(this)
        runBlocking {
            for ((path, json) in changes) {
                when (path) {
                    Paths.DEVICE -> decode(json) { S4hJson.decodeFromString(DeviceLink.serializer(), it) }
                        ?.let { store.saveLink(it) }
                    Paths.STATUS -> decode(json) { S4hJson.decodeFromString(PhoneStatus.serializer(), it) }
                        ?.let { StatusSync.apply(this@WatchListenerService, it) }
                }
            }
        }
    }

    private inline fun <T> decode(json: String, parse: (String) -> T): T? = try {
        parse(json)
    } catch (e: Exception) {
        Log.w("S4hListener", "bad payload: $json", e)
        null
    }
}

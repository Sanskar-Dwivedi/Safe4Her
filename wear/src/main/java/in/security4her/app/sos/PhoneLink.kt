package `in`.security4her.app.sos

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import `in`.security4her.app.data.DeviceLink
import `in`.security4her.app.data.Paths
import `in`.security4her.app.data.PhoneStatus
import `in`.security4her.app.data.S4hJson
import `in`.security4her.app.data.WatchStore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CancellationException

/** Wear Data Layer transport to the phone app (path A). */
object PhoneLink {
    private const val TAG = "S4hPhoneLink"

    /** Sends [json] to every reachable phone with our app; returns how many accepted the message. */
    suspend fun send(context: Context, path: String, json: String): Int {
        val nodes = try {
            Wearable.getCapabilityClient(context)
                .getCapability(Paths.CAP_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "capability lookup failed", e)
            emptySet()
        }
        val client = Wearable.getMessageClient(context)
        var sent = 0
        for (node in nodes) {
            try {
                client.sendMessage(node.id, path, json.toByteArray(Charsets.UTF_8)).await()
                sent++
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "sendMessage $path to ${node.displayName} failed", e)
            }
        }
        return sent
    }

    suspend fun isPhoneReachable(context: Context): Boolean = try {
        Wearable.getCapabilityClient(context)
            .getCapability(Paths.CAP_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes.isNotEmpty()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }

    /**
     * Pulls the latest /s4h/device and /s4h/status DataItems (they may predate this install),
     * and asks the phone to re-link if we have no device token yet.
     */
    suspend fun syncFromPhone(context: Context) {
        val store = WatchStore.get(context)
        readDataItem(context, Paths.DEVICE)?.let { json ->
            runCatching { S4hJson.decodeFromString(DeviceLink.serializer(), json) }.getOrNull()?.let { store.saveLink(it) }
        }
        readDataItem(context, Paths.STATUS)?.let { json ->
            runCatching { S4hJson.decodeFromString(PhoneStatus.serializer(), json) }.getOrNull()?.let {
                StatusSync.apply(context, it)
            }
        }
        if (store.linkNow() == null) send(context, Paths.LINK_REQUEST, "{}")
    }

    private suspend fun readDataItem(context: Context, path: String): String? = try {
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()
        val items = Wearable.getDataClient(context).getDataItems(uri).await()
        try {
            items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getString(Paths.DATA_KEY_JSON) }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "getDataItems $path failed", e)
        null
    }
}

/** Applies a phone status update to the local incident (shared by the listener service and startup sync). */
object StatusSync {
    suspend fun apply(context: Context, status: PhoneStatus) {
        val store = WatchStore.get(context)
        val incident = store.incidentNow() ?: return
        if (incident.incidentId != status.incidentId) return
        store.savePhoneStatus(status)
        store.updateIncident(status.incidentId) {
            it.copy(
                phoneAck = true,
                shareUrl = it.shareUrl ?: status.shareUrl,
                ended = it.ended || status.state == "resolved" || status.state == "cancelled",
                endedAt = it.endedAt ?: if (status.state != "active") System.currentTimeMillis() else null,
            )
        }
    }
}

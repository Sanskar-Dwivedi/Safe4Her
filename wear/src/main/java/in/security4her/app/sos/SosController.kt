package `in`.security4her.app.sos

import android.content.Context
import android.util.Log
import `in`.security4her.app.data.ActiveIncident
import `in`.security4her.app.data.Fix
import `in`.security4her.app.data.IncidentRef
import `in`.security4her.app.data.Paths
import `in`.security4her.app.data.S4hJson
import `in`.security4her.app.data.SosLocationMsg
import `in`.security4her.app.data.SosStartMsg
import `in`.security4her.app.data.WatchStore
import `in`.security4her.app.service.SafetyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The watch SOS pipeline:
 * countdown → persist one incident (UUID) → path A (Data Layer to phone) and path B (HTTPS to server)
 * in parallel → retries. Every path carries the same incidentId, so the phone and server deduplicate.
 */
object SosController {
    private const val TAG = "S4hSos"
    const val COUNTDOWN_SECONDS = 5
    private const val PHONE_ATTEMPTS = 3
    private const val ACK_TIMEOUT_MS = 5_000L

    data class Countdown(val trigger: String, val secondsLeft: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _countdown = MutableStateFlow<Countdown?>(null)
    val countdown: StateFlow<Countdown?> = _countdown.asStateFlow()
    private var countdownJob: Job? = null

    /** Starts the cancellable countdown unless one is running or an incident is already live. */
    fun beginCountdown(context: Context, trigger: String) {
        val app = context.applicationContext
        synchronized(this) {
            if (countdownJob?.isActive == true) return
            countdownJob = scope.launch {
                val live = WatchStore.get(app).incidentNow()
                if (live != null && !live.ended) return@launch
                for (s in COUNTDOWN_SECONDS downTo 1) {
                    _countdown.value = Countdown(trigger, s)
                    Haptics.tick(app)
                    delay(1_000)
                }
                _countdown.value = null
                // Run outside the countdown job so a late "cancel" can't interrupt delivery half-way.
                scope.launch { start(app, trigger) }
            }
        }
    }

    fun cancelCountdown() {
        synchronized(this) {
            countdownJob?.cancel()
            countdownJob = null
            _countdown.value = null
        }
    }

    /** Creates (or returns the already-live) incident and starts delivery. */
    suspend fun start(context: Context, trigger: String): ActiveIncident {
        val app = context.applicationContext
        val store = WatchStore.get(app)
        val fresh = ActiveIncident(
            incidentId = UUID.randomUUID().toString(),
            trigger = trigger,
            createdAt = System.currentTimeMillis(),
            test = store.linkNow()?.testMode ?: true,
        )
        val incident = store.createIncidentIfNone(fresh)
        if (incident.incidentId != fresh.incidentId) return incident // already live: never a second incident

        Haptics.sosStarted(app)
        SafetyService.ensureRunning(app) // keeps sending location + retrying while active
        scope.launch { deliverToPhone(app, incident.incidentId) }
        scope.launch {
            val fix = Locator.quickFix(app, 6_000)
            if (fix != null) {
                store.updateIncident(incident.incidentId) { it.copy(fix = fix) }
                PhoneLink.send(app, Paths.SOS_LOCATION, locationJson(incident.incidentId, fix))
            }
            deliverToServer(app, incident.incidentId)
        }
        return incident
    }

    /** Path A: send immediately (no waiting for GPS), wait for the phone's ack, retry a few times. */
    suspend fun deliverToPhone(context: Context, incidentId: String) {
        val store = WatchStore.get(context)
        repeat(PHONE_ATTEMPTS) { attempt ->
            val current = store.incidentNow()?.takeIf { it.incidentId == incidentId && !it.ended } ?: return
            if (current.phoneAck) return
            val json = S4hJson.encodeToString(SosStartMsg.serializer(), current.startMsg())
            val nodes = PhoneLink.send(context, Paths.SOS_START, json)
            store.updateIncident(incidentId) { it.copy(phoneNodes = nodes, phoneAttempts = it.phoneAttempts + 1) }
            if (nodes > 0) {
                val acked = withTimeoutOrNull(ACK_TIMEOUT_MS) {
                    store.incident.filterNotNull().first { it.incidentId != incidentId || it.phoneAck }
                }
                if (acked != null) return
            }
            if (attempt < PHONE_ATTEMPTS - 1) delay(3_000)
        }
        Log.w(TAG, "phone did not acknowledge $incidentId; SafetyService keeps retrying")
    }

    /** Path B: direct HTTPS. On failure, WorkManager retries when a network is available. */
    suspend fun deliverToServer(context: Context, incidentId: String) {
        val store = WatchStore.get(context)
        val incident = store.incidentNow()?.takeIf { it.incidentId == incidentId } ?: return
        if (incident.serverAccepted) return
        val link = store.linkNow()
        if (link == null) {
            store.updateIncident(incidentId) { it.copy(serverError = "Watch not linked to phone app") }
            return
        }
        ServerLink.post(link, incident.serverRequest("start")).fold(
            onSuccess = { r ->
                store.updateIncident(incidentId) {
                    it.copy(serverAccepted = true, serverError = null, shareUrl = r.shareUrl ?: it.shareUrl,
                        telegramSent = it.telegramSent + r.telegramSent)
                }
            },
            onFailure = { e ->
                store.updateIncident(incidentId) { it.copy(serverError = e.message ?: "network error") }
                ServerSyncWorker.enqueue(context)
            },
        )
    }

    /** Periodic update while active: fresh location to both paths, and re-send start where not yet delivered. */
    suspend fun heartbeat(context: Context) {
        val store = WatchStore.get(context)
        val incident = store.incidentNow()?.takeIf { !it.ended } ?: return
        val fix = Locator.quickFix(context, 10_000)
        if (fix != null) store.updateIncident(incident.incidentId) { it.copy(fix = fix) }
        if (!incident.phoneAck) {
            deliverToPhone(context, incident.incidentId)
        } else if (fix != null) {
            PhoneLink.send(context, Paths.SOS_LOCATION, locationJson(incident.incidentId, fix))
        }
        val link = store.linkNow() ?: return
        if (!incident.serverAccepted) {
            deliverToServer(context, incident.incidentId)
        } else if (fix != null) {
            ServerLink.post(link, incident.serverRequest("location", fix))
        }
    }

    /** "I'm safe" on the watch: end locally first, then tell the phone and the server. */
    fun end(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val store = WatchStore.get(app)
            val incident = store.incidentNow()?.takeIf { !it.ended } ?: return@launch
            store.updateIncident(incident.incidentId) { it.copy(ended = true, endedAt = System.currentTimeMillis()) }
            val ref = S4hJson.encodeToString(IncidentRef.serializer(), IncidentRef(incident.incidentId))
            PhoneLink.send(app, Paths.SOS_CANCEL, ref)
            val link = store.linkNow()
            if (link != null && incident.serverAccepted) ServerLink.post(link, incident.serverRequest("cancel"))
        }
    }

    fun dismiss(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val store = WatchStore.get(app)
            if (store.incidentNow()?.ended == true) store.clearIncident()
        }
    }

    private fun locationJson(incidentId: String, fix: Fix) = S4hJson.encodeToString(
        SosLocationMsg.serializer(),
        SosLocationMsg(incidentId, fix.lat, fix.lng, fix.accuracyM, fix.at),
    )
}

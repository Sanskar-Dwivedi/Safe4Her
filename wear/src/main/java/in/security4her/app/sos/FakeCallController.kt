package `in`.security4her.app.sos

import android.content.Context
import android.os.SystemClock
import `in`.security4her.app.data.FakeCallRequest
import `in`.security4her.app.data.Paths
import `in`.security4her.app.data.S4hJson
import `in`.security4her.app.data.WatchStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** All watch entry points share one request/ack path. ACK means handled, not necessarily ringing. */
object FakeCallController {
    enum class Result(val message: String) {
        IDLE("Ready to request a fake call"),
        SENDING("Requesting fake call…"),
        ACCEPTED("Phone accepted — check its call screen or incoming-call notification"),
        UNREACHABLE("Phone not reachable — reconnect and try again"),
        REJECTED("Phone declined — check its fake-call and notification settings"),
        TIMEOUT("No confirmation — check your phone before trying again"),
        FAILED("Request failed — reconnect and try again"),
        SOS("Fake call paused during SOS"),
    }
    data class State(val result: Result = Result.IDLE, val at: Long = 0L) {
        val busy get() = result == Result.SENDING
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val inFlight = AtomicBoolean(false)
    private var lastRequestAt = Long.MIN_VALUE / 2
    private val _status = MutableStateFlow(State())
    val status = _status.asStateFlow()

    fun request(context: Context) {
        if (TwistRuntime.practice.value || !inFlight.compareAndSet(false, true)) return
        val now = SystemClock.elapsedRealtime()
        // The existing phone receiver rate-limits to 10 seconds too. Ignore duplicate taps.
        if (now - lastRequestAt < 10_000) {
            inFlight.set(false)
            return
        }
        lastRequestAt = now
        val app = context.applicationContext
        _status.value = State(Result.SENDING)
        scope.launch {
            val request = FakeCallRequest(UUID.randomUUID().toString(), System.currentTimeMillis())
            val reply = CompletableDeferred<Boolean>()
            pending[request.requestId] = reply
            try {
                val result = withTimeoutOrNull(12_000) {
                    if (TwistRuntime.practice.value || SosController.countdown.value != null ||
                        WatchStore.get(app).incidentNow()?.ended == false) return@withTimeoutOrNull Result.SOS
                    val sent = PhoneLink.send(app, Paths.FAKE_CALL_START,
                        S4hJson.encodeToString(FakeCallRequest.serializer(), request))
                    if (sent == 0) Result.UNREACHABLE
                    else if (reply.await()) Result.ACCEPTED else Result.REJECTED
                } ?: Result.TIMEOUT
                report(app, result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                report(app, Result.FAILED)
            } finally {
                pending.remove(request.requestId)
                reply.cancel()
                inFlight.set(false)
            }
        }
    }

    private fun report(context: Context, result: Result) {
        _status.value = State(result, System.currentTimeMillis())
        if (result == Result.ACCEPTED) Haptics.fakeCallAccepted(context) else Haptics.failure(context)
    }

    fun acknowledge(requestId: String, accepted: Boolean) {
        pending[requestId]?.complete(accepted)
    }
}

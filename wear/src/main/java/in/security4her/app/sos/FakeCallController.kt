package `in`.security4her.app.sos

import android.content.Context
import `in`.security4her.app.data.FakeCallRequest
import `in`.security4her.app.data.Paths
import `in`.security4her.app.data.S4hJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A twist requests a fake call on the reachable phone; a phone acknowledgement confirms it was handled. */
object FakeCallController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    fun request(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val request = FakeCallRequest(UUID.randomUUID().toString(), System.currentTimeMillis())
            val reply = CompletableDeferred<Boolean>()
            pending[request.requestId] = reply
            _status.value = "Requesting fake call…"
            val sent = PhoneLink.send(app, Paths.FAKE_CALL_START, S4hJson.encodeToString(FakeCallRequest.serializer(), request))
            val accepted = sent > 0 && withTimeoutOrNull(7_000) { reply.await() } == true
            pending.remove(request.requestId)
            if (accepted) {
                _status.value = "Phone alert ready"
                Haptics.fakeCallAccepted(app)
            } else {
                _status.value = if (sent == 0) "Phone not reachable" else "Phone could not start fake call"
                Haptics.failure(app)
            }
            val shown = _status.value
            delay(6_000)
            if (_status.value == shown) _status.value = null
        }
    }

    fun acknowledge(requestId: String, accepted: Boolean) {
        pending[requestId]?.complete(accepted)
    }
}

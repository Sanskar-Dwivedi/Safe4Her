package `in`.security4her.app.sos

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Readiness is separate from the saved ON switch. Samples are never persisted or uploaded. */
object TwistRuntime {
    enum class Status(val label: String) {
        STOPPED("Open the app to resume twist detection"),
        STARTING("Starting twist detection…"),
        READY("Ready — awake screen only"),
        ASLEEP("Raise wrist to wake, then twist twice"),
        PRACTICE("Practice mode — no calls or gesture SOS"),
        SOS("Twist paused during SOS"),
        UNAVAILABLE("Motion sensors unavailable"),
        STALLED("No sensor samples — wake screen or reopen app"),
        BLOCKED("Could not start — reopen app to resume"),
        COOLDOWN("Call requested — wait 30 seconds before twisting again"),
    }
    private val _status = MutableStateFlow(Status.STOPPED)
    val status = _status.asStateFlow()
    private val _practice = MutableStateFlow(false)
    val practice = _practice.asStateFlow()
    private var lastTrigger = Long.MIN_VALUE / 2

    fun report(status: Status) { _status.value = status }
    fun setPractice(on: Boolean) { _practice.value = on }
    fun coolingDown() = SystemClock.elapsedRealtime() - lastTrigger < 30_000
    @Synchronized fun claimTrigger(): Boolean {
        if (_practice.value || coolingDown()) return false
        lastTrigger = SystemClock.elapsedRealtime()
        return true
    }
}

package `in`.security4her.app.sos

import kotlin.math.abs
import kotlin.math.sqrt

/** Bounded calibration: larger angles require more deliberate rotations. */
enum class TwistStrength(val label: String, val angleRadians: Float) {
    GENTLE("Gentle", 0.42f), STANDARD("Standard", 0.56f), FIRM("Firm", 0.73f);
}

/**
 * Integrates rotation along the initial movement's 3D axis, independent of wrist orientation.
 * Four alternating strokes must each travel far enough. Speed peaks alone never count.
 * Timestamps are monotonic sensor timestamps, not wall-clock time.
 */
class WristTwistLogic(private val strength: TwistStrength = TwistStrength.STANDARD) {
    data class Sample(
        val strokes: Int = 0,
        val degrees: Int = 0,
        val hint: String = "Twist out, back, out, back",
        val detected: Boolean = false,
    )
    private var axis: FloatArray? = null
    private var previousAt = 0L
    private var startedAt = 0L
    private var movingAt = 0L
    private var direction = 1
    private var angle = 0f
    private var counted = false
    private var strokes = 0
    private var restUntil = 0L

    fun onSample(x: Float, y: Float, z: Float, accelerationG: Float, nowMs: Long): Sample {
        val gap = nowMs - previousAt
        previousAt = nowMs
        if (nowMs < restUntil) return Sample(hint = "Detected — relax your wrist")
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !accelerationG.isFinite()) {
            reset()
            return Sample(hint = "Waiting for valid sensor data")
        }
        if (gap !in 1..120) {
            reset()
            return Sample(hint = "Sensors ready — try again")
        }
        if (accelerationG !in 0.65f..1.7f) {
            reset()
            return Sample(hint = "Keep your arm steady; rotate your wrist")
        }
        if (axis != null && (nowMs - startedAt > 4_500 || nowMs - movingAt > 1_100)) {
            reset()
            return Sample(hint = "Too much pause — do two continuous twists")
        }
        val speed = sqrt(x * x + y * y + z * z)
        if (axis == null) {
            if (speed < 0.8f) return Sample()
            axis = floatArrayOf(x / speed, y / speed, z / speed)
            startedAt = nowMs
            movingAt = nowMs
        }
        val a = axis!!
        val rate = x * a[0] + y * a[1] + z * a[2]
        if (speed > 1.2f && abs(rate) < speed * 0.55f) {
            reset()
            return Sample(hint = "Direction changed — rotate around your forearm")
        }
        if (abs(rate) < 0.35f) return snapshot()
        movingAt = nowMs
        val sign = if (rate > 0) 1 else -1
        if (sign != direction) {
            if (abs(rate) < 0.65f) return snapshot()
            if (!counted) {
                reset()
                return Sample(hint = "Turn a little farther before reversing")
            }
            direction = sign
            angle = 0f
            counted = false
        }
        angle += abs(rate) * gap / 1_000f
        if (!counted && angle >= strength.angleRadians) {
            strokes++
            counted = true
            if (strokes == 4) {
                val result = Sample(4, degrees(), "Twist recognised", detected = true)
                reset()
                restUntil = nowMs + 1_500
                return result
            }
        }
        return snapshot()
    }

    private fun degrees() = Math.toDegrees(angle.toDouble()).toInt()
    private fun snapshot() = Sample(strokes, degrees(), if (counted) "Now reverse your wrist" else "Keep turning")
    private fun reset() {
        axis = null
        strokes = 0
        direction = 1
        angle = 0f
        counted = false
    }
}

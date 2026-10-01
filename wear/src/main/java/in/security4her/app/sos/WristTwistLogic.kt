package `in`.security4her.app.sos

import kotlin.math.abs

/** Four quick alternating wrist rotations (out, back, out, back), with a cooldown. */
class WristTwistLogic(
    private val peakSpeedRadPerSec: Float = 2.5f,
    private val releaseSpeedRadPerSec: Float = 1.0f,
    private val minGapMs: Long = 140,
    private val maxGapMs: Long = 750,
    private val patternWindowMs: Long = 2_200,
    private val cooldownMs: Long = 30_000,
) {
    private var axis = -1
    private var peaks = 0
    private var lastSign = 0
    private var firstPeakAt = 0L
    private var lastPeakAt = 0L
    private var ready = true
    private var lastTriggerAt = Long.MIN_VALUE / 2

    /** Rates are gyroscope x/y in radians per second; acceleration is measured in g. */
    fun onSample(xRate: Float, yRate: Float, accelerationG: Float, nowMs: Long): Boolean {
        if (nowMs - lastTriggerAt < cooldownMs) return false
        if (accelerationG !in 0.65f..1.7f) {
            reset()
            return false
        }
        if (peaks > 0 && nowMs - firstPeakAt > patternWindowMs) reset()

        val candidateAxis = if (abs(xRate) > abs(yRate)) 0 else 1
        val rate = if (axis == 0) xRate else if (axis == 1) yRate else if (candidateAxis == 0) xRate else yRate
        if (abs(rate) < releaseSpeedRadPerSec) {
            ready = true
            return false
        }
        if (abs(rate) < peakSpeedRadPerSec) return false
        val sign = if (rate > 0) 1 else -1
        if (!ready && sign == lastSign) return false

        if (peaks > 0) {
            val gap = nowMs - lastPeakAt
            if (gap < minGapMs) return false
            if (gap > maxGapMs || sign == lastSign) reset()
        }
        if (peaks == 0) {
            axis = candidateAxis
            firstPeakAt = nowMs
        }
        peaks++
        lastSign = sign
        lastPeakAt = nowMs
        ready = false
        if (peaks < 4) return false

        lastTriggerAt = nowMs
        reset()
        return true
    }

    private fun reset() {
        axis = -1
        peaks = 0
        lastSign = 0
        firstPeakAt = 0L
        lastPeakAt = 0L
        ready = true
    }
}

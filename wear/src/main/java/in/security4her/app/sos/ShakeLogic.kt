package `in`.security4her.app.sos

/**
 * Pure shake-pattern logic (unit-tested): [peaksNeeded] acceleration peaks above [thresholdG]
 * within [windowMs], peaks at least [minPeakGapMs] apart, then a [cooldownMs] quiet period.
 */
class ShakeLogic(
    private val thresholdG: Double = 2.5,
    private val peaksNeeded: Int = 3,
    private val windowMs: Long = 2_000,
    private val minPeakGapMs: Long = 150,
    private val cooldownMs: Long = 10_000,
) {
    private val peaks = ArrayDeque<Long>()
    private var lastPeakAt = Long.MIN_VALUE / 2
    private var lastTriggerAt = Long.MIN_VALUE / 2

    /** Feed one sample (magnitude in g, time in ms). Returns true when the pattern completes. */
    fun onSample(g: Double, nowMs: Long): Boolean {
        if (nowMs - lastTriggerAt < cooldownMs) return false
        if (g < thresholdG || nowMs - lastPeakAt < minPeakGapMs) return false
        lastPeakAt = nowMs
        peaks.addLast(nowMs)
        while (peaks.isNotEmpty() && nowMs - peaks.first() > windowMs) peaks.removeFirst()
        if (peaks.size >= peaksNeeded) {
            peaks.clear()
            lastTriggerAt = nowMs
            return true
        }
        return false
    }
}

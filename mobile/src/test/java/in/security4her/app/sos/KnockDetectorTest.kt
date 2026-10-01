package `in`.security4her.app.sos

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Feeds synthetic accelerometer data (phone upright, gravity on y) through the detector at a given sample rate. */
class KnockDetectorTest {
    private fun run(
        signal: (t: Long) -> Float, ms: Long = 7_000, stepMs: Long = 10, threshold: Float = KnockDetector.DEFAULT_THRESHOLD,
        handShake: Float = 0.3f, flat: Boolean = false, covered: Boolean? = null,
    ): Int {
        var fired = 0
        val d = KnockDetector(threshold = threshold, onTriple = { fired++ }).also { it.covered = covered }
        val rnd = Random(7)
        var t = 0L
        while (t < ms) {
            // A hand-held phone drifts slowly and has sensor noise
            val drift = if (flat) 0f else (0.8 * sin(t / 700.0)).toFloat()
            val n = { (rnd.nextFloat() - 0.5f) * handShake }
            if (flat) d.feed(n(), n(), 9.81f + signal(t) + n(), t) // lying screen up: gravity on z, knocks into the surface
            else d.feed(drift + n(), 9.81f + signal(t) + n(), n(), t)
            t += stepMs
        }
        return fired
    }

    /** A knock as a smoothing phone sensor reports it: a hit, a rebound, then a short fading ring. */
    private fun knocks(vararg at: Long, size: Float = 5f): (Long) -> Float = { t ->
        at.sumOf { k ->
            val d = t - k
            when {
                d < 0 -> 0.0
                d < 20 -> size.toDouble()
                d < 40 -> -0.45 * size
                d < 160 -> 0.25 * size * exp(-d / 40.0) * sin(d / 8.0)
                else -> 0.0
            }
        }.toFloat()
    }

    private val walking: (Long) -> Float = { t -> val p = t % 520; if (p < 180) (4.0 * sin(PI * p / 180)).toFloat() else 0f }

    @Test fun normalKnocksFire() = assertEquals(1, run(knocks(2_000, 2_500, 3_000)))

    @Test fun softerHandHeldKnocksFire() = assertEquals(1, run(knocks(2_000, 2_450, 2_900, size = 3.6f)))

    @Test fun slowerRelaxedKnocksFire() = assertEquals(1, run(knocks(2_000, 2_900, 3_800)))

    @Test fun quickKnocksFire() = assertEquals(1, run(knocks(2_000, 2_250, 2_500)))

    @Test fun worksAt50Hz() = assertEquals(1, run(knocks(2_000, 2_500, 3_000), stepMs = 20))

    @Test fun worksAt200Hz() = assertEquals(1, run(knocks(2_000, 2_500, 3_000), stepMs = 5))

    @Test fun lightTapsDoNothing() = assertEquals(0, run(knocks(2_000, 2_500, 3_000, size = 1.8f)))

    @Test fun lightTapsCountOnLightSetting() = assertEquals(1, run(knocks(2_000, 2_500, 3_000, size = 2.5f), threshold = 2.8f))

    @Test fun twoKnocksDoNothing() = assertEquals(0, run(knocks(2_000, 2_500)))

    @Test fun fourKnocksDoNothing() = assertEquals(0, run(knocks(2_000, 2_400, 2_800, 3_200)))

    @Test fun knocksTooFarApartDoNothing() = assertEquals(0, run(knocks(2_000, 3_400, 4_800)))

    @Test fun holdingThePhoneDoesNothing() = assertEquals(0, run({ 0f }, ms = 15_000, handShake = 0.8f))

    @Test fun walkingDoesNothing() = assertEquals(0, run(walking, ms = 12_000))

    @Test fun walkingAt50HzDoesNothing() = assertEquals(0, run(walking, ms = 12_000, stepMs = 20))

    @Test fun runningDoesNothing() = assertEquals(0, run({ t -> if (t % 330 < 40) 12f else 0f }, ms = 10_000))

    @Test fun knocksWhileWalkingFire() = assertEquals(1, run({ walking(it) + knocks(2_050, 2_500, 2_950, size = 6f)(it) }))

    @Test fun aShoveOrFallDoesNothing() = assertEquals(0, run({ t -> if (t in 2_000..2_900) 20f * sin(t / 25.0).toFloat() else 0f }))

    // A phone lying still on a table: only sensor noise. Light taps there spike hard, so normal knocks don't count.
    private val tableNoise = 0.04f

    @Test fun tapsOnAPhoneLyingOnATableDoNothing() =
        assertEquals(0, run(knocks(4_000, 4_500, 5_000, size = 5f), handShake = tableNoise, flat = true))

    @Test fun hardKnocksOnATableStillFire() =
        assertEquals(1, run(knocks(4_000, 4_500, 5_000, size = 12f), handShake = tableNoise, flat = true))

    @Test fun flatAndStillInAPocketFires() = // proximity says covered: sitting still, phone flat in a pocket
        assertEquals(1, run(knocks(4_000, 4_500, 5_000, size = 5f), handShake = tableNoise, flat = true, covered = true))

    @Test fun flatInAHandFires() = // a hand's tremor means it isn't resting on anything
        assertEquals(1, run(knocks(4_000, 4_500, 5_000, size = 5f), flat = true))

    @Test fun knocksRightAfterPuttingItDownFire() = // not still for long enough to count as resting
        assertEquals(1, run(knocks(1_200, 1_700, 2_200, size = 5f), handShake = tableNoise, flat = true))
}

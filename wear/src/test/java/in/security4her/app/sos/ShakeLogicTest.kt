package `in`.security4her.app.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeLogicTest {
    private fun feed(logic: ShakeLogic, samples: List<Pair<Double, Long>>) = samples.count { (g, t) -> logic.onSample(g, t) }

    @Test fun threeStrongPeaksWithinWindowTrigger() {
        val logic = ShakeLogic()
        assertFalse(logic.onSample(3.0, 1_000))
        assertFalse(logic.onSample(3.0, 1_400))
        assertTrue(logic.onSample(3.0, 1_800))
    }

    @Test fun normalMovementNeverTriggers() {
        val logic = ShakeLogic()
        // walking/running: frequent samples but below 2.5 g
        val samples = (0 until 2_000).map { i -> (1.0 + (i % 7) * 0.2) to (i * 20L) }
        assertEquals(0, feed(logic, samples))
    }

    @Test fun peaksTooFarApartDoNotTrigger() {
        val logic = ShakeLogic()
        assertEquals(0, feed(logic, listOf(3.0 to 0L, 3.0 to 1_500L, 3.0 to 3_600L, 3.0 to 5_700L)))
    }

    @Test fun samplesInsideOnePeakCountOnce() {
        val logic = ShakeLogic()
        // one physical jolt produces several consecutive high samples 20 ms apart
        assertEquals(0, feed(logic, listOf(3.0 to 0L, 3.2 to 20L, 3.1 to 40L, 2.9 to 60L)))
    }

    @Test fun cooldownPreventsRepeatTriggers() {
        val logic = ShakeLogic()
        assertEquals(1, feed(logic, listOf(3.0 to 0L, 3.0 to 300L, 3.0 to 600L, 3.0 to 900L, 3.0 to 1_200L, 3.0 to 1_500L)))
        assertEquals(1, feed(logic, listOf(3.0 to 11_000L, 3.0 to 11_300L, 3.0 to 11_600L)))
    }
}

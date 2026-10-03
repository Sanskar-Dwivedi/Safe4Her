package `in`.security4her.app.sos

import org.junit.Assert.assertEquals
import org.junit.Test

/** Screen on/off flips as Android reports them, at the times given (ms); ticks every 50 ms like the service's timer. */
class PowerPressDetectorTest {
    private fun run(vararg flips: Long, until: Long = (flips.maxOrNull() ?: 0) + 3_000): Int {
        var fired = 0
        val d = PowerPressDetector { fired++ }
        var i = 0
        var t = 0L
        while (t <= until) {
            while (i < flips.size && flips[i] <= t) { d.press(flips[i]); i++ }
            d.tick(t)
            t += 50
        }
        return fired
    }

    @Test fun threePressesHalfASecondApartFire() = assertEquals(1, run(1_000, 1_500, 2_000))

    @Test fun quickPressesFire() = assertEquals(1, run(1_000, 1_250, 1_500))

    @Test fun relaxedPressesFire() = assertEquals(1, run(1_000, 2_200, 3_400))

    // The rhythm she's told to use: one a second, which the phone's own Emergency SOS never counts
    @Test fun onePressASecondFires() = assertEquals(1, run(1_000, 2_000, 3_000))

    @Test fun pressesMoreThanTwoSecondsApartDoNothing() = assertEquals(0, run(1_000, 3_200, 5_400))

    @Test fun twoPressesDoNothing() = assertEquals(0, run(1_000, 1_500))

    @Test fun checkingTheTimeDoesNothing() = assertEquals(0, run(1_000, 4_000, 9_000, 12_000)) // on, off, on, off, slowly

    @Test fun notificationWakeAndTimeoutDoNothing() = assertEquals(0, run(1_000, 31_000))

    @Test fun androidsFivePressSosCountsOnce() = assertEquals(1, run(1_000, 1_300, 1_600, 1_900, 2_200))

    // Seen on Android 15: presses 0.54 s apart, but the off and on broadcasts arrived only 74 ms apart
    @Test fun bunchedScreenEventsStillCount() = assertEquals(1, run(1_000, 1_074, 1_700))

    @Test fun anEventDeliveredTwiceCountsOnce() = assertEquals(0, run(1_000, 1_005, 1_600))

    @Test fun twoSeparateTriplesFireTwice() = assertEquals(2, run(1_000, 1_500, 2_000, 6_000, 6_500, 7_000))

    @Test fun armsOnTheThirdPressAtOnce() {
        var fired = 0
        val d = PowerPressDetector { fired++ }
        d.press(1_000); d.press(1_500)
        assertEquals(0, fired)
        d.press(2_000) // no waiting: the buzz comes right away so she stops before the phone's own 5-press SOS
        assertEquals(1, fired)
        d.press(2_300); d.press(2_600) // she kept pressing anyway: still one alarm
        assertEquals(1, fired)
    }
}

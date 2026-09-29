package `in`.security4her.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** "HELP HELP HELP" must trigger; everyday speech, songs and TV must not. */
class VoicePhraseLogicTest {
    private fun help(at: Long, conf: Float = 1f, loud: Boolean = true, dur: Long = 400) =
        VoicePhraseLogic.Word("help", conf, at, at + dur, loud)

    private fun other(at: Long, dur: Long = 300) = VoicePhraseLogic.Word("[unk]", 1f, at, at + dur, true)

    private fun count(vararg words: VoicePhraseLogic.Word): Int {
        val l = VoicePhraseLogic()
        return words.count { l.onWord(it) }
    }

    // ---- should trigger

    @Test fun shoutedThreeTimes() = assertEquals(1, count(help(0), help(600), help(1_200)))

    @Test fun withPausesBetween() = assertEquals(1, count(help(0), help(1_500), help(3_000)))

    @Test fun fourHelpsTriggersOnce() = assertEquals(1, count(help(0), help(600), help(1_200), help(1_800)))

    @Test fun afterSomeUnrelatedSpeech() = assertEquals(1, count(other(0), other(400), help(2_000), help(2_600), help(3_200)))

    // ---- should not trigger (false cases)

    @Test fun onlyTwice() = assertEquals(0, count(help(0), help(600)))

    @Test fun helpMeHelpMeHelpMe() = assertEquals(0, count(help(0), other(450), help(900), other(1_350), help(1_800), other(2_250)))

    @Test fun helloHelloHello() = assertEquals(0, count(
        VoicePhraseLogic.Word("hello", 1f, 0, 400, true), VoicePhraseLogic.Word("hello", 1f, 600, 1_000, true),
        VoicePhraseLogic.Word("hello", 1f, 1_200, 1_600, true),
    ))

    @Test fun conversationMentioningHelp() = assertEquals(0, count(other(0), help(500), other(1_000), other(1_500), help(4_000), other(4_500), help(9_000)))

    @Test fun songWithWordsBetween() = assertEquals(0, count(help(0), other(500), other(900), help(1_600), other(2_100), help(3_000)))

    @Test fun tooSlow() = assertEquals(0, count(help(0), help(2_500), help(5_000)))

    @Test fun lowConfidenceWords() = assertEquals(0, count(help(0, conf = 0.4f), help(600, conf = 0.5f), help(1_200, conf = 0.3f)))

    @Test fun oneUncertainWordBreaksIt() = assertEquals(0, count(help(0), help(600, conf = 0.4f), help(1_200)))

    @Test fun quietBackgroundTv() = assertEquals(0, count(help(0, loud = false), help(600, loud = false), help(1_200, loud = false)))

    @Test fun cooldownStopsARepeat() = assertEquals(1, count(help(0), help(600), help(1_200), help(3_000), help(3_600), help(4_200)))

    @Test fun afterCooldownItWorksAgain() = assertEquals(2, count(help(0), help(600), help(1_200), help(20_000), help(20_600), help(21_200)))

    @Test fun staleRunExpires() {
        val l = VoicePhraseLogic()
        l.onWord(help(0)); l.onWord(help(600))
        assertEquals(2, l.count)
        l.expire(3_000) // the next word may still be on its way from the recogniser
        assertEquals(2, l.count)
        l.expire(6_000)
        assertEquals(0, l.count)
        assertEquals(false, l.onWord(help(6_100)))
    }
}

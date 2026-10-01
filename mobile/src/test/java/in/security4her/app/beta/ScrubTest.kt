package `in`.security4her.app.beta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Personal data must never survive scrubbing, while the useful technical part of a message must. */
class ScrubTest {
    private fun s(t: String, names: List<String> = emptyList()) = Beta.scrubText(t, names)

    @Test fun phoneNumbers() {
        assertEquals("Text to [number] failed", s("Text to +91 98765 43210 failed"))
        assertEquals("call [number]", s("call 9876543210"))
    }

    @Test fun aadhaar() = assertFalse(s("Aadhaar 2345 6789 4821 verified").contains("4821"))

    @Test fun coordinates() {
        assertEquals("fix at [location] ok", s("fix at 21.145812, 79.088155 ok"))
        assertFalse(s("lat 21.1458123").contains("21.1458"))
    }

    @Test fun linksAndEmails() {
        assertEquals("open [link] now", s("open https://atharvalakhe.github.io/s4h-live/?t=s4h-abc now"))
        assertEquals("mail [email]", s("mail priya.sharma@gmail.com"))
    }

    @Test fun names() = assertEquals("Text to [name] failed", s("Text to Priya failed", listOf("Priya")))

    @Test fun keepsTechnicalDetail() {
        val stack = "java.lang.IllegalStateException: camera busy\n  at in.security4her.app.sos.Evidence.start(Evidence.kt:142)"
        assertEquals(stack, s(stack))
        assertEquals("SMS failed: RESULT_ERROR_NO_SERVICE (code 4)", s("SMS failed: RESULT_ERROR_NO_SERVICE (code 4)"))
    }

    @Test fun ipAddresses() = assertEquals("watch at [ip]", s("watch at 192.168.1.23"))
}

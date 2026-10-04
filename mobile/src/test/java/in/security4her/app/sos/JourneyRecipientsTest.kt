package `in`.security4her.app.sos

import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyRecipientsTest {
    @Test fun practiceMessagesUseOnlyTheTestNumber() {
        assertEquals(listOf("+919999999999"), journeyRecipients(true, "+91 99999 99999", listOf("112", "+918888888888")))
    }
    @Test fun missingTestNumberNeverFallsBackToContacts() {
        assertEquals(emptyList<String>(), journeyRecipients(true, "", listOf("112")))
    }
    @Test fun liveRecipientsAreValidatedAndDeduplicated() {
        assertEquals(listOf("+918888888888"), journeyRecipients(false, "112", listOf("+91 88888 88888", "+918888888888", "")))
    }
}

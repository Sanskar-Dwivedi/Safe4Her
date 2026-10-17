package `in`.security4her.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Who counts as a child, from the DigiLocker date of birth (dd-MM-yyyy). */
class AgeTest {
    private val today = LocalDate.of(2026, 10, 17)

    @Test fun fourteenYearOld() = assertEquals(14, Age.yearsOf("21-06-2012", today))

    @Test fun dayBeforeEighteenthBirthdayIsSeventeen() = assertEquals(17, Age.yearsOf("18-10-2008", today))

    @Test fun eighteenthBirthdayIsAnAdult() = assertEquals(18, Age.yearsOf("17-10-2008", today))

    @Test fun adult() = assertEquals(25, Age.yearsOf("14-03-2001", today))

    @Test fun unreadableDateIsUnknown() = assertNull(Age.yearsOf("2012/06/21", today))
}

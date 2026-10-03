package `in`.security4her.app.sos

internal fun journeyRecipients(testMode: Boolean, testNumber: String, contacts: List<String>): List<String> =
    (if (testMode) listOf(testNumber) else contacts)
        .map { number -> number.filter { it.isDigit() || it == '+' } }
        .filter { number -> number.count(Char::isDigit) >= 3 }
        .distinct()

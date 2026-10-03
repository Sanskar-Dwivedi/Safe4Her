package `in`.security4her.app.sos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class S4heTest {
    private val secret = S4he.randomKey()
    private val audio = Random(1).nextBytes(50_000)

    private fun pack(plain: ByteArray = audio, keyId: String = "24fa3170-2026100212", start: Long = 1_000L): ByteArray {
        val dataKey = S4he.randomKey()
        val h = S4he.Header(
            keyId = keyId, wrapped = S4he.wrap(S4he.kek(secret), dataKey, keyId), case = "24fa3170-case", name = "audio-1.m4a",
            kind = "audio", start = start, end = start + 15_000, sha256 = S4he.sha256(plain),
        )
        return S4he.seal(h, dataKey, plain)
    }

    @Test fun roundTrip() {
        val (h, plain) = S4he.open(pack(), secret)
        assertArrayEquals(audio, plain)
        assertEquals("audio-1.m4a", h.name)
    }

    @Test fun wrongRecoveryCodeFails() {
        val e = assertThrows(S4he.BadPackage::class.java) { S4he.open(pack(), S4he.randomKey()) }
        assertEquals("wrong recovery code", e.message)
    }

    @Test fun changedTimestampInHeaderFails() {
        val p = pack(start = 1_000L)
        val s = String(p, Charsets.ISO_8859_1).replace("\"start\":1000", "\"start\":9000").toByteArray(Charsets.ISO_8859_1)
        assertNotEquals(String(p, Charsets.ISO_8859_1), String(s, Charsets.ISO_8859_1))
        assertThrows(S4he.BadPackage::class.java) { S4he.open(s, secret) }
    }

    @Test fun changedRecordingFails() {
        val p = pack(); p[p.size - 100] = (p[p.size - 100].toInt() xor 1).toByte()
        assertEquals("the package was changed", assertThrows(S4he.BadPackage::class.java) { S4he.open(p, secret) }.message)
    }

    @Test fun garbageAndTruncatedFilesAreRejected() {
        assertThrows(S4he.BadPackage::class.java) { S4he.open(Random(2).nextBytes(500), secret) }
        assertThrows(S4he.BadPackage::class.java) { S4he.open(pack().copyOfRange(0, 40), secret) }
        val huge = "S4HE".toByteArray() + byteArrayOf(1, 0x7f, 0x7f, 0x7f, 0x7f) + ByteArray(100) // header length lies
        assertThrows(S4he.BadPackage::class.java) { S4he.open(huge, secret) }
    }

    @Test fun nothingReadableWithoutTheKey() {
        val p = String(pack("SOS from Asha at Sitabuldi".toByteArray()), Charsets.ISO_8859_1)
        assert("Sitabuldi" !in p)
    }

    @Test fun eachHourGetsItsOwnKeyAndNoncesNeverRepeat() {
        assertNotEquals(S4he.sha256(pack()), S4he.sha256(pack())) // same input, fresh key + nonce each time
    }

    @Test fun recoveryCodeRoundTripsAndCatchesTypos() {
        val code = S4he.encodeCode(secret)
        assertEquals(9, code.split("-").size)
        assertArrayEquals(secret, S4he.decodeCode(code))
        assertArrayEquals(secret, S4he.decodeCode(code.lowercase().replace("-", " ")))
        val typo = code.replaceFirst(code[0], if (code[0] == 'A') 'B' else 'A')
        assertNull(S4he.decodeCode(typo))
        assertNull(S4he.decodeCode("too short"))
    }
}

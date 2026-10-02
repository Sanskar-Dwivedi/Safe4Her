package `in`.security4her.app.sos

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted evidence package (.s4he). Pure JVM, no Android, so it is unit-tested.
 *
 *   "S4HE" | version (1 byte) | header length (4 bytes) | header JSON | 12-byte nonce | AES-256-GCM ciphertext + tag
 *
 * The header and everything before it are the GCM associated data, so changing a timestamp or key id breaks the
 * package. Each SOS gets a fresh random key per UTC hour (the data key); the header carries that key encrypted with
 * the owner's key-encryption key, which is derived from her 256-bit recovery secret. With the recovery code alone,
 * any package can be opened offline, years later, on a fresh install: no server keeps keys.
 */
object S4he {
    private val MAGIC = "S4HE".toByteArray()
    private const val VERSION: Byte = 1
    private const val MAX_HEADER = 4_096
    const val MAX_PACKAGE = 64 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    @Serializable
    data class Header(
        val v: Int = 1,
        val keyId: String, // which hourly key: incident + UTC hour
        val wrapped: String, // that key, encrypted for the owner's recovery secret
        val case: String,
        val name: String, // the piece's file name; a renamed package still opens
        val kind: String, // audio | video | watch_audio | manifest
        val start: Long,
        val end: Long,
        val sha256: String, // of the plaintext: the fingerprint taken when it was recorded
    )

    class BadPackage(msg: String) : Exception(msg)

    fun randomKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    /** Key-encryption key from the recovery secret (domain-separated, so the secret itself is never a cipher key). */
    fun kek(secret: ByteArray): ByteArray {
        require(secret.size == 32) { "recovery secret must be 256 bits" }
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal("s4h-evidence-kek-v1".toByteArray())
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad)
        }.doFinal(data)

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = java.util.Base64.getDecoder().decode(s)

    fun wrap(kek: ByteArray, dataKey: ByteArray, keyId: String): String {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        return b64(nonce + gcm(Cipher.ENCRYPT_MODE, kek, nonce, "s4h-key|$keyId".toByteArray(), dataKey))
    }

    fun unwrap(kek: ByteArray, wrapped: String, keyId: String): ByteArray {
        val b = unb64(wrapped)
        if (b.size != 12 + 32 + 16) throw BadPackage("bad wrapped key")
        return try { gcm(Cipher.DECRYPT_MODE, kek, b.copyOfRange(0, 12), "s4h-key|$keyId".toByteArray(), b.copyOfRange(12, b.size)) }
        catch (e: javax.crypto.AEADBadTagException) { throw BadPackage("wrong recovery code") }
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Encrypts [plain] with [dataKey]; [header] must already carry the wrapped key and the plaintext fingerprint. */
    fun seal(header: Header, dataKey: ByteArray, plain: ByteArray): ByteArray {
        val h = json.encodeToString(Header.serializer(), header).toByteArray()
        require(h.size <= MAX_HEADER)
        val prefix = ByteArrayOutputStream().apply {
            write(MAGIC); write(VERSION.toInt()); write(ByteBuffer.allocate(4).putInt(h.size).array()); write(h)
        }.toByteArray()
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        return prefix + nonce + gcm(Cipher.ENCRYPT_MODE, dataKey, nonce, prefix, plain)
    }

    fun isPackage(start: ByteArray) = start.size >= 4 && start.copyOfRange(0, 4).contentEquals(MAGIC)

    /** Reads the header without decrypting (bounded: never trusts sizes from the file). */
    fun header(bytes: ByteArray): Pair<Header, Int> {
        if (bytes.size > MAX_PACKAGE || bytes.size < 4 + 1 + 4 + 12 + 16) throw BadPackage("not an evidence package")
        if (!isPackage(bytes)) throw BadPackage("not an evidence package")
        if (bytes[4] != VERSION) throw BadPackage("made by a newer version of Security4Her")
        val len = ByteBuffer.wrap(bytes, 5, 4).int
        if (len !in 2..MAX_HEADER || 9 + len + 12 + 16 > bytes.size) throw BadPackage("damaged package")
        val h = runCatching { json.decodeFromString(Header.serializer(), String(bytes, 9, len)) }.getOrElse { throw BadPackage("damaged package") }
        return h to (9 + len)
    }

    /** Decrypts with the recovery secret and checks the recorded fingerprint. Throws [BadPackage] on any mismatch. */
    fun open(bytes: ByteArray, secret: ByteArray): Pair<Header, ByteArray> {
        val (h, at) = header(bytes)
        val key = unwrap(kek(secret), h.wrapped, h.keyId)
        val plain = try {
            gcm(Cipher.DECRYPT_MODE, key, bytes.copyOfRange(at, at + 12), bytes.copyOfRange(0, at), bytes.copyOfRange(at + 12, bytes.size))
        } catch (e: javax.crypto.AEADBadTagException) { throw BadPackage("the package was changed") }
        if (sha256(plain) != h.sha256) throw BadPackage("fingerprint doesn't match")
        return h to plain
    }

    // ---- recovery code: 256 bits in Crockford base32 + a 10-bit check, as 9 groups of 6 ----
    private const val ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encodeCode(secret: ByteArray): String {
        require(secret.size == 32)
        val check = MessageDigest.getInstance("SHA-256").digest(secret)
        val bits = StringBuilder()
        secret.forEach { bits.append(String.format("%8s", Integer.toBinaryString(it.toInt() and 0xff)).replace(' ', '0')) }
        bits.append(String.format("%8s", Integer.toBinaryString(check[0].toInt() and 0xff)).replace(' ', '0'))
        bits.append(String.format("%8s", Integer.toBinaryString(check[1].toInt() and 0xff)).replace(' ', '0').substring(0, 6))
        // 256 + 14 = 270 bits = 54 chars
        val chars = (0 until 54).map { ALPHA[bits.substring(it * 5, it * 5 + 5).toInt(2)] }.joinToString("")
        return chars.chunked(6).joinToString("-")
    }

    /** Null when the code is mistyped (the check bits catch nearly every typo). */
    fun decodeCode(text: String): ByteArray? {
        val clean = text.uppercase().filter { it.isLetterOrDigit() }.map { c -> when (c) { 'O' -> '0'; 'I', 'L' -> '1'; else -> c } }.joinToString("")
        if (clean.length != 54 || clean.any { it !in ALPHA }) return null
        val bits = clean.map { String.format("%5s", Integer.toBinaryString(ALPHA.indexOf(it))).replace(' ', '0') }.joinToString("")
        val secret = ByteArray(32) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        return secret.takeIf { encodeCode(it).replace("-", "") == clean }
    }
}

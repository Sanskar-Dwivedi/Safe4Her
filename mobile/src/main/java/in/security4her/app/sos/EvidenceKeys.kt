package `in`.security4her.app.sos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Store
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keys for the encrypted evidence copies. The 256-bit recovery secret is generated on this phone, shown to her once as
 * a recovery code, and kept here encrypted by an Android Keystore key that never leaves the phone's secure hardware.
 * Deleting the app deletes that Keystore key, which is why the recovery code is the only way back in afterwards.
 */
object EvidenceKeys {
    private const val ALIAS = "s4h_evidence_v1"

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build(), // no user-authentication requirement: it must work mid-SOS with the screen locked
            )
        }.generateKey()
    }

    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun unb64(s: String) = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    private fun store(ctx: Context, secret: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        Store.prefs(ctx).edit().putString("ev_secret", b64(c.iv + c.doFinal(secret))).apply()
    }

    @Volatile private var cached: ByteArray? = null

    fun hasSecret(ctx: Context) = Store.prefs(ctx).contains("ev_secret")

    @Synchronized
    fun secret(ctx: Context): ByteArray {
        cached?.let { return it }
        val p = Store.prefs(ctx)
        val enc = p.getString("ev_secret", null)
        val s = if (enc == null) S4he.randomKey().also { store(ctx, it); AppLog.info("Evidence", "Created this phone's evidence recovery key") }
        else unb64(enc).let { b ->
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, b.copyOfRange(0, 12))) }
                .doFinal(b.copyOfRange(12, b.size))
        }
        cached = s
        return s
    }

    /** After a reinstall: use the recovery code she typed, so old and new evidence share one code. */
    @Synchronized
    fun adopt(ctx: Context, secret: ByteArray) {
        store(ctx, secret); cached = secret
        Store.prefs(ctx).edit().putBoolean("ev_code_saved", true).apply()
    }

    fun code(ctx: Context) = S4he.encodeCode(secret(ctx))
    fun codeSaved(ctx: Context) = Store.prefs(ctx).getBoolean("ev_code_saved", false)
    fun markCodeSaved(ctx: Context) = Store.prefs(ctx).edit().putBoolean("ev_code_saved", true).apply()

    /** incident + UTC hour: a new random key every hour of an SOS, so one leaked key opens at most an hour of one SOS. */
    fun keyId(incidentId: String, at: Long): String =
        incidentId.take(8) + "-" + SimpleDateFormat("yyyyMMddHH", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at))

    /** The wrapped data key for [keyId], made the first time it's needed. Only the wrapped form is ever stored. */
    @Synchronized
    private fun wrappedKey(ctx: Context, keyId: String): String {
        val p = Store.prefs(ctx)
        p.getString("ev_dek_$keyId", null)?.let { return it }
        val w = S4he.wrap(S4he.kek(secret(ctx)), S4he.randomKey(), keyId)
        p.edit().putString("ev_dek_$keyId", w).apply()
        return w
    }

    fun seal(ctx: Context, case: String, name: String, kind: String, start: Long, end: Long, plain: ByteArray): ByteArray {
        val id = keyId(case, start)
        val w = wrappedKey(ctx, id)
        val dataKey = S4he.unwrap(S4he.kek(secret(ctx)), w, id)
        return S4he.seal(S4he.Header(keyId = id, wrapped = w, case = case, name = name, kind = kind, start = start, end = end, sha256 = S4he.sha256(plain)), dataKey, plain)
    }

    fun open(ctx: Context, bytes: ByteArray): Pair<S4he.Header, ByteArray> = S4he.open(bytes, secret(ctx))
}

/**
 * Bringing evidence back after the app was deleted: she picks the folder in Downloads, types her recovery code, and
 * every package that code opens is decrypted back into the app. Works offline; file names don't matter.
 */
object EvidenceRestore {
    data class Result(val cases: Int, val pieces: Int, val unreadable: Int)

    private fun children(ctx: Context, tree: Uri, docId: String, depth: Int, out: MutableList<Pair<Uri, String>>) {
        if (depth > 3 || out.size > 5_000) return
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1).orEmpty(); val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) children(ctx, tree, id, depth + 1, out)
                else if (name.endsWith(".s4he")) out += DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
            }
        }
    }

    /** Throws [S4he.BadPackage] with "wrong recovery code" when the code opens none of the files. */
    fun restore(ctx: Context, tree: Uri, secret: ByteArray): Result {
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val files = mutableListOf<Pair<Uri, String>>()
        children(ctx, tree, DocumentsContract.getTreeDocumentId(tree), 0, files)
        val opened = mutableListOf<Triple<Uri, S4he.Header, ByteArray>>()
        var unreadable = 0
        files.forEach { (uri, _) ->
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val (h, plain) = S4he.open(bytes, secret)
                opened += Triple(uri, h, plain)
            }.onFailure { unreadable++ }
        }
        if (opened.isEmpty() && files.isNotEmpty()) throw S4he.BadPackage("wrong recovery code")
        if (!EvidenceKeys.hasSecret(ctx)) EvidenceKeys.adopt(ctx, secret)
        var pieces = 0
        val byCase = opened.groupBy { it.second.case }
        byCase.forEach { (caseId, items) ->
            val manifest = items.firstOrNull { it.second.kind == "manifest" }
            val pieceItems = items.filter { it.second.kind != "manifest" }
            pieceItems.forEach { (uri, h, plain) ->
                val f = java.io.File(Evidence.dir(ctx, caseId), h.name)
                if (!f.exists()) { f.writeBytes(plain); pieces++ }
                EvidenceBackup.adoptRef(ctx, caseId, h.name, uri.toString())
            }
            Evidence.importCase(ctx, caseId, manifest?.third, pieceItems.map { it.second })
        }
        AppLog.ok("Evidence", "Restored $pieces recordings from ${byCase.size} SOS cases" + if (unreadable > 0) " ($unreadable files didn't open with this code)" else "")
        return Result(byCase.size, pieces, unreadable)
    }
}

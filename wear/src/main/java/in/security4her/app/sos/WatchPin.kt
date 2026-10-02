package `in`.security4her.app.sos

import android.content.Context
import `in`.security4her.app.data.DeviceLink

/**
 * Checks the safety PIN on the watch against the salted hash the phone sent, with the same lockout as the phone:
 * 5 free tries, then 30 s, doubling up to an hour. Without it, anyone holding her wrist could end the SOS.
 */
object WatchPin {
    sealed interface Check {
        data object Ok : Check
        data class Wrong(val left: Int) : Check
        data class Locked(val seconds: Int) : Check
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("s4h_pin", Context.MODE_PRIVATE)

    fun required(link: DeviceLink?) = link != null && link.pinHash.isNotBlank() && link.pinSalt.isNotBlank() && link.pinIter > 0

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun check(ctx: Context, link: DeviceLink, entered: String): Check {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val until = p.getLong("locked_until", 0)
        if (now < until) return Check.Locked(((until - now) / 1000).toInt() + 1)
        val ok = runCatching {
            val spec = javax.crypto.spec.PBEKeySpec(entered.toCharArray(), unhex(link.pinSalt), link.pinIter, 256)
            val h = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            java.security.MessageDigest.isEqual(h, unhex(link.pinHash))
        }.getOrDefault(false)
        if (ok) { p.edit().putInt("fails", 0).remove("locked_until").apply(); return Check.Ok }
        val fails = p.getInt("fails", 0) + 1
        `in`.security4her.app.beta.WatchBeta.event("security", "warning", "sos_pin_wrong_watch", null, mapOf("fails" to fails))
        if (fails < 5) { p.edit().putInt("fails", fails).apply(); return Check.Wrong(5 - fails) }
        val lockMs = (30_000L shl (fails - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)
        p.edit().putInt("fails", fails).putLong("locked_until", now + lockMs).apply()
        return Check.Locked((lockMs / 1000).toInt())
    }

    fun message(c: Check) = when (c) {
        Check.Ok -> ""
        is Check.Wrong -> "Wrong PIN"
        is Check.Locked -> if (c.seconds >= 120) "Locked ${c.seconds / 60} min" else "Locked ${c.seconds} s"
    }
}

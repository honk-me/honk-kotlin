package app.honkme

import java.security.SecureRandom

/**
 * UUIDv7 (RFC 9562): 48-bit Unix milliseconds, then random bits. Used for automatic idempotency
 * keys; store one with your job to retry the same event across restarts.
 */
public object UuidV7 {
    private val random = SecureRandom()

    @JvmStatic
    @JvmOverloads
    public fun generate(unixMillis: Long = System.currentTimeMillis()): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        for (i in 0 until 6) b[i] = (unixMillis ushr (8 * (5 - i))).toByte()
        b[6] = ((b[6].toInt() and 0x0F) or 0x70).toByte() // version 7
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte() // variant 10
        val hex = b.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

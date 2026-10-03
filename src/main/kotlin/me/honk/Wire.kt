package me.honk

import java.math.BigDecimal
import java.math.BigInteger
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Limits from contracts/openapi.yaml (and the server's validator). */
public object Limits {
    public const val BODY_BYTES: Int = 16 * 1024
    public const val MESSAGE_BYTES: Int = 8192
    public const val TITLE: Int = 160
    public const val SOURCE: Int = 64
    public const val ENVIRONMENT: Int = 32
    public const val CHANNEL: Int = 64
    public const val GROUP_KEY: Int = 128
    public const val URL_BYTES: Int = 2048
    public const val METADATA_KEYS: Int = 16
    public const val METADATA_STRING: Int = 512
    public const val MIN_TTL_SECONDS: Int = 60
    public const val MAX_TTL_SECONDS: Int = 86_400
    public const val MAX_SOURCE_SEQUENCE: Long = (1L shl 53) - 1
}

internal object Wire {
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val METADATA_KEY = Regex("^[A-Za-z0-9_.-]{1,64}$")

    /** Applies defaults, validates (unless [validate] is false) and returns the JSON body. */
    fun encode(m: Message, defaults: Defaults, validate: Boolean): String {
        fun nonEmpty(s: String?) = s?.takeIf { it.isNotEmpty() }
        val body = LinkedHashMap<String, Any>()
        nonEmpty(m.title)?.let { body["title"] = it }
        body["message"] = m.message
        m.severity?.let { body["severity"] = it.value }
        m.priority?.let { body["priority"] = it.value }
        m.category?.let { body["category"] = it.value }
        (nonEmpty(m.source) ?: nonEmpty(defaults.source))?.let { body["source"] = it }
        (nonEmpty(m.environment) ?: nonEmpty(defaults.environment))?.let { body["environment"] = it }
        (nonEmpty(m.channel) ?: nonEmpty(defaults.channel))?.let { body["channel"] = it }
        nonEmpty(m.groupKey)?.let { body["group_key"] = it }
        m.eventType?.let { body["event_type"] = it.value }
        m.occurredAt?.let { body["occurred_at"] = TIMESTAMP.format(it) }
        nonEmpty(m.url)?.let { body["url"] = it }
        nonEmpty(m.imageUrl)?.let { body["image_url"] = it }
        if (m.metadata.isNotEmpty()) body["metadata"] = m.metadata
        m.ttlSeconds?.let { body["ttl_seconds"] = it }
        m.sourceSequence?.let { body["source_sequence"] = it }

        val errors = ArrayList<FieldError>()
        if (validate) check(body, errors) else if (m.message.isEmpty()) errors += FieldError("message", "required", "message is required")
        var json = ""
        if (errors.isEmpty()) {
            json = Json.write(body)
            val size = json.toByteArray(Charsets.UTF_8).size
            if (size > Limits.BODY_BYTES) errors += FieldError("body", "too_long", "the JSON body is $size bytes; Honk accepts at most 16 KiB")
        }
        if (errors.isNotEmpty()) throw HonkValidationException.local(errors)
        return json
    }

    fun validIdempotencyKey(key: String): Boolean = key.length in 1..128 && key.all { it.code in 0x21..0x7E }

    private fun check(b: Map<String, Any>, e: MutableList<FieldError>) {
        val m = b["message"] as String
        when {
            m.isEmpty() -> e += FieldError("message", "required", "message is required")
            m.utf8Bytes() > Limits.MESSAGE_BYTES ->
                e += FieldError("message", "too_long", "must be at most ${Limits.MESSAGE_BYTES} bytes of UTF-8 (got ${m.utf8Bytes()})")
            m.isBlank() -> e += FieldError("message", "too_short", "must not be blank")
            hasControl(m, allowBreaks = true) ->
                e += FieldError("message", "invalid_format", "must not contain control characters other than line breaks and tabs")
        }
        shortText(b["title"], "title", Limits.TITLE, e)
        shortText(b["source"], "source", Limits.SOURCE, e)
        shortText(b["environment"], "environment", Limits.ENVIRONMENT, e)
        shortText(b["channel"], "channel", Limits.CHANNEL, e)
        shortText(b["group_key"], "group_key", Limits.GROUP_KEY, e)

        val seq = b["source_sequence"] as Long?
        if (seq != null && (seq < 0 || seq > Limits.MAX_SOURCE_SEQUENCE)) {
            e += FieldError("source_sequence", "out_of_range", "must be between 0 and 2^53-1")
        }
        if (b["group_key"] == null) {
            if (b["event_type"] == EventType.RECOVERY.value) e += FieldError("group_key", "requires_group_key", "recovery events require group_key")
            if (seq != null) e += FieldError("source_sequence", "requires_group_key", "source_sequence requires group_key")
        }
        (b["url"] as String?)?.let {
            if (!validUrl(it, image = false)) e += FieldError("url", "invalid_format", "must be an https URL without credentials, at most 2048 bytes")
        }
        (b["image_url"] as String?)?.let {
            if (!validUrl(it, image = true)) {
                e += FieldError("image_url", "invalid_format", "must be an https URL without credentials or fragment, at most 2048 bytes")
            }
        }
        @Suppress("UNCHECKED_CAST")
        (b["metadata"] as Map<String, Any?>?)?.let { md ->
            if (md.size > Limits.METADATA_KEYS) e += FieldError("metadata", "too_long", "at most ${Limits.METADATA_KEYS} keys")
            for (key in md.keys.sorted()) {
                val field = "metadata.$key"
                if (!METADATA_KEY.matches(key)) {
                    e += FieldError(field, "invalid_format", "keys must match [A-Za-z0-9_.-]{1,64}")
                    continue
                }
                val problem = when (val v = md[key]) {
                    is String -> if (v.codePointLength() > Limits.METADATA_STRING || hasControl(v, allowBreaks = true)) {
                        "strings must be at most ${Limits.METADATA_STRING} characters without control characters"
                    } else {
                        null
                    }
                    is Boolean, is Int, is Long, is Short, is Byte, is BigInteger, is BigDecimal -> null
                    is Double -> if (v.isFinite()) null else "numbers must be finite"
                    is Float -> if (v.isFinite()) null else "numbers must be finite"
                    else -> "values must be strings, numbers or booleans (got ${v?.javaClass?.simpleName ?: "null"})"
                }
                if (problem != null) e += FieldError(field, "invalid_format", problem)
            }
        }
        (b["ttl_seconds"] as Int?)?.let {
            if (it !in Limits.MIN_TTL_SECONDS..Limits.MAX_TTL_SECONDS) e += FieldError("ttl_seconds", "out_of_range", "must be between 60 and 86400")
        }
    }

    private fun shortText(value: Any?, field: String, max: Int, e: MutableList<FieldError>) {
        val s = (value as String? ?: return).trim()
        when {
            s.isEmpty() -> e += FieldError(field, "too_short", "must not be empty")
            s.codePointLength() > max -> e += FieldError(field, "too_long", "must be at most $max characters")
            hasControl(s, allowBreaks = false) -> e += FieldError(field, "invalid_format", "must not contain control characters or line breaks")
        }
    }

    /** The server's rule: Unicode control characters (C0, DEL, C1) and U+2028/U+2029. */
    fun hasControl(s: String, allowBreaks: Boolean): Boolean = s.codePoints().anyMatch { cp ->
        if (allowBreaks && (cp == '\n'.code || cp == '\t'.code || cp == '\r'.code)) {
            false
        } else {
            Character.getType(cp) == Character.CONTROL.toInt() || cp == 0x2028 || cp == 0x2029
        }
    }

    /** Syntactic check matching the server: https, a host, no credentials, no spaces or backslashes, ≤ 2048 bytes. */
    fun validUrl(raw: String, image: Boolean): Boolean {
        val s = raw.trim()
        if (s.isEmpty() || s.utf8Bytes() > Limits.URL_BYTES || hasControl(s, false) || s.contains(' ') || s.contains('\\')) return false
        if (!s.startsWith("https://", ignoreCase = true) || (image && s.contains('#'))) return false
        val authority = s.substring(8).takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty() || authority.contains('@')) return false
        var host = authority
        var port = ""
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return false
            host = authority.substring(0, end + 1)
            val after = authority.substring(end + 1)
            if (after.isNotEmpty()) {
                if (!after.startsWith(":")) return false
                port = after.substring(1)
            }
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1)
            }
        }
        if (host.isEmpty() || host == "[]") return false
        if (port.isNotEmpty()) {
            val n = if (port.length <= 5 && port.all { it in '0'..'9' }) port.toInt() else return false
            if (n !in 1..65535) return false
        }
        return true
    }

    private fun String.utf8Bytes() = toByteArray(Charsets.UTF_8).size
    private fun String.codePointLength() = codePointCount(0, length)
}

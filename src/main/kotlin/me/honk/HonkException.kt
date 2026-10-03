package me.honk

import java.time.Duration

/** One invalid field, from the server (`error.fields[]`) or local validation. */
public data class FieldError @JvmOverloads constructor(
    /** Wire name: `group_key`, `metadata.region`, `Idempotency-Key`, `body`, … */
    val field: String,
    /** `required`, `too_long`, `too_short`, `invalid_enum`, `invalid_format`, `out_of_range`, `not_allowed`, `requires_group_key`, … */
    val code: String,
    val message: String? = null,
)

/**
 * Base class of every error thrown by `send`. Unchecked, so Java callers catch only what they
 * care about. Retryable errors ([isRetryable]) can be sent again later with the same
 * [idempotencyKey] without creating duplicates.
 */
public open class HonkException internal constructor(
    message: String,
    /** HTTP status, when the server answered. */
    public val status: Int? = null,
    /** API error code (`invalid_key`, `quota_exceeded`, …) or `network_error` / `timeout` / `validation_failed`. */
    public val code: String? = null,
    /** Server request id (`req_…`). */
    public val requestId: String? = null,
    /** The Idempotency-Key that was used. */
    public val idempotencyKey: String? = null,
    /** HTTP attempts made (0 when rejected locally). */
    public val attempts: Int = 0,
    /** The server's Retry-After, when present. */
    public val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** True when sending the same event again later (same idempotency key) may succeed. */
    public open val isRetryable: Boolean get() = false
}

/** The message is invalid: rejected locally ([isLocal]) or by the server (400, 413, 415, 422). */
public class HonkValidationException internal constructor(
    message: String,
    /** Every invalid field. */
    public val fields: List<FieldError>,
    /** True when the SDK rejected the message before sending anything. */
    public val isLocal: Boolean,
    status: Int? = null,
    code: String? = "validation_failed",
    requestId: String? = null,
    idempotencyKey: String? = null,
    attempts: Int = 0,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts) {
    internal companion object {
        fun local(fields: List<FieldError>): HonkValidationException = HonkValidationException(
            "Invalid Honk message: " + fields.joinToString("; ") { "${it.field} ${it.message ?: it.code}" },
            fields,
            isLocal = true,
        )
    }
}

/** 401/403: invalid or revoked key, `priority_not_allowed`, suspended project or workspace. */
public class HonkAuthException internal constructor(
    message: String, status: Int?, code: String?, requestId: String?, idempotencyKey: String?, attempts: Int,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts)

/** 429 after retries: `quota_exceeded` (daily, until UTC midnight) or `rate_limited`. See [retryAfter]. */
public class HonkQuotaException internal constructor(
    message: String, status: Int?, code: String?, requestId: String?, idempotencyKey: String?, attempts: Int, retryAfter: Duration?,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts, retryAfter) {
    override val isRetryable: Boolean get() = true
}

/** 409 `idempotency_conflict`: the key was already used with a different payload in the last 24 hours. */
public class HonkConflictException internal constructor(
    message: String, status: Int?, code: String?, requestId: String?, idempotencyKey: String?, attempts: Int,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts)

/** Honk could not be reached on any attempt before the deadline. */
public open class HonkNetworkException internal constructor(
    message: String, code: String, idempotencyKey: String?, attempts: Int, cause: Throwable?,
) : HonkException(message, null, code, null, idempotencyKey, attempts, null, cause) {
    override val isRetryable: Boolean get() = true
}

/** Attempts timed out; the message may or may not have been stored (retrying with the same key is safe). */
public class HonkTimeoutException internal constructor(
    message: String, idempotencyKey: String?, attempts: Int, cause: Throwable?,
) : HonkNetworkException(message, "timeout", idempotencyKey, attempts, cause)

/** 5xx on every attempt before the deadline. */
public class HonkServerException internal constructor(
    message: String, status: Int?, code: String?, requestId: String?, idempotencyKey: String?, attempts: Int, retryAfter: Duration?,
) : HonkException(message, status, code, requestId, idempotencyKey, attempts, retryAfter) {
    override val isRetryable: Boolean get() = true
}

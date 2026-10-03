package me.honk

import java.time.Instant

/**
 * One event for `POST /v1/messages`. Only [message] is required; null fields are omitted, so the
 * server defaults apply (severity info, priority normal, source "api", environment "default",
 * channel "general", event type event, TTL 3600 s).
 *
 * Kotlin: `Message("nightly pg_dump took 42 s", title = "Backup finished", severity = Severity.BEEP)`.
 * Java: `Message.builder().title("Backup finished").message("nightly pg_dump took 42 s").beep().build()`.
 */
public data class Message @JvmOverloads constructor(
    /** Plain text, 1–8192 bytes of UTF-8. Line breaks and tabs are allowed. */
    val message: String,
    /** One line, ≤ 160 characters. Defaults to the first line of [message]. */
    val title: String? = null,
    /** The Honk scale: LIGHT, BEEP, LOUD, LONG, BLAST (aliases of INFO … CRITICAL). */
    val severity: Severity? = null,
    val priority: Priority? = null,
    val category: Category? = null,
    /** ≤ 64 characters. Default: the client's `defaults.source`, else "api". */
    val source: String? = null,
    /** ≤ 32 characters. */
    val environment: String? = null,
    /** ≤ 64 characters. */
    val channel: String? = null,
    /**
     * ≤ 128 characters. Messages with the same key (per environment, source and channel) form one
     * group. One key per customer request ("requests/<id>"); a shared key only for repeats of the
     * same problem.
     */
    val groupKey: String? = null,
    val eventType: EventType? = null,
    /** When it happened at the source (informational). */
    val occurredAt: Instant? = null,
    /** An https link shown as "Open link" (no credentials, ≤ 2048 bytes). */
    val url: String? = null,
    /** An https image the server fetches after ingestion (no credentials or fragment). */
    val imageUrl: String? = null,
    /** ≤ 16 keys matching `[A-Za-z0-9_.-]{1,64}`; values are strings (≤ 512 characters), numbers or booleans. */
    val metadata: Map<String, Any> = emptyMap(),
    /** Push lifetime, 60–86400 seconds (default 3600). */
    val ttlSeconds: Int? = null,
    /** Monotonic counter per source stream (0 … 2^53-1). Needs [groupKey]. */
    val sourceSequence: Long? = null,
    /** A stable key for this event (1–128 printable ASCII characters), reused on every retry. */
    val idempotencyKey: String? = null,
) {
    /** A builder pre-filled with this message. */
    public fun toBuilder(): Builder = Builder(this)

    /** Fluent builder, for Java and for the Kotlin helper lambdas (`honk.loud("t", "m") { groupKey("x") }`). */
    public class Builder internal constructor(from: Message?) {
        private var message: String? = from?.message
        private var title: String? = from?.title
        private var severity: Severity? = from?.severity
        private var priority: Priority? = from?.priority
        private var category: Category? = from?.category
        private var source: String? = from?.source
        private var environment: String? = from?.environment
        private var channel: String? = from?.channel
        private var groupKey: String? = from?.groupKey
        private var eventType: EventType? = from?.eventType
        private var occurredAt: Instant? = from?.occurredAt
        private var url: String? = from?.url
        private var imageUrl: String? = from?.imageUrl
        private val metadata: LinkedHashMap<String, Any> = LinkedHashMap(from?.metadata ?: emptyMap())
        private var ttlSeconds: Int? = from?.ttlSeconds
        private var sourceSequence: Long? = from?.sourceSequence
        private var idempotencyKey: String? = from?.idempotencyKey

        public fun message(message: String): Builder = apply { this.message = message }

        /** Appends a line to the message text. */
        public fun line(line: String): Builder = apply { message = if (message.isNullOrEmpty()) line else "$message\n$line" }
        public fun title(title: String?): Builder = apply { this.title = title }
        public fun severity(severity: Severity?): Builder = apply { this.severity = severity }

        /** A horn or canonical name, case-insensitively ("loud", "WARNING"). */
        public fun severity(severity: String): Builder = apply { this.severity = Severity.parse(severity) }

        /** A light honk (info). */
        public fun light(): Builder = severity(Severity.INFO)

        /** A beep-beep (success). */
        public fun beep(): Builder = severity(Severity.SUCCESS)

        /** A loud honk (warning). */
        public fun loud(): Builder = severity(Severity.WARNING)

        /** A long honk (error). Java: `longHonk()` (`long` is a Java keyword) or `error()`. */
        @JvmName("longHonk")
        public fun long(): Builder = severity(Severity.ERROR)

        /** A blast (critical). */
        public fun blast(): Builder = severity(Severity.CRITICAL)
        public fun info(): Builder = light()
        public fun success(): Builder = beep()
        public fun warning(): Builder = loud()
        public fun error(): Builder = long()
        public fun critical(): Builder = blast()
        public fun priority(priority: Priority?): Builder = apply { this.priority = priority }
        public fun priority(priority: String): Builder = apply { this.priority = Priority.parse(priority) }
        public fun category(category: Category?): Builder = apply { this.category = category }
        public fun category(category: String): Builder = apply { this.category = Category.parse(category) }
        public fun source(source: String?): Builder = apply { this.source = source }
        public fun environment(environment: String?): Builder = apply { this.environment = environment }
        public fun channel(channel: String?): Builder = apply { this.channel = channel }
        public fun groupKey(groupKey: String?): Builder = apply { this.groupKey = groupKey }
        public fun eventType(eventType: EventType?): Builder = apply { this.eventType = eventType }
        public fun eventType(eventType: String): Builder = apply { this.eventType = EventType.parse(eventType) }
        public fun occurredAt(occurredAt: Instant?): Builder = apply { this.occurredAt = occurredAt }
        public fun url(url: String?): Builder = apply { this.url = url }
        public fun imageUrl(imageUrl: String?): Builder = apply { this.imageUrl = imageUrl }

        /** Adds one metadata entry (string, number or boolean). */
        public fun meta(key: String, value: Any): Builder = apply { metadata[key] = value }

        /** Merges metadata entries. */
        public fun metadata(entries: Map<String, Any>): Builder = apply { metadata.putAll(entries) }
        public fun ttlSeconds(ttlSeconds: Int?): Builder = apply { this.ttlSeconds = ttlSeconds }
        public fun sourceSequence(sourceSequence: Long?): Builder = apply { this.sourceSequence = sourceSequence }
        public fun idempotencyKey(idempotencyKey: String?): Builder = apply { this.idempotencyKey = idempotencyKey }

        /** @throws HonkValidationException when the message text is missing. */
        public fun build(): Message {
            val text = message ?: throw HonkValidationException.local(listOf(FieldError("message", "required", "message is required")))
            return Message(
                text, title, severity, priority, category, source, environment, channel, groupKey, eventType,
                occurredAt, url, imageUrl, LinkedHashMap(metadata), ttlSeconds, sourceSequence, idempotencyKey,
            )
        }
    }

    public companion object {
        @JvmStatic public fun builder(): Builder = Builder(null)

        @JvmStatic public fun of(message: String): Message = Message(message)

        /** A problem for [groupKey] (opens or continues its incident); severity defaults to LONG (error). */
        @JvmStatic
        public fun problem(groupKey: String, title: String?, message: String): Builder =
            builder().groupKey(groupKey).eventType(EventType.PROBLEM).severity(Severity.ERROR).title(title).message(message)

        /** A recovery for [groupKey] (closes its open incident); severity defaults to BEEP (success). */
        @JvmStatic
        public fun recovery(groupKey: String, title: String?, message: String): Builder =
            builder().groupKey(groupKey).eventType(EventType.RECOVERY).severity(Severity.SUCCESS).title(title).message(message)

        @JvmStatic public fun light(title: String?, message: String): Builder = builder().light().title(title).message(message)

        @JvmStatic public fun beep(title: String?, message: String): Builder = builder().beep().title(title).message(message)

        @JvmStatic public fun loud(title: String?, message: String): Builder = builder().loud().title(title).message(message)

        /** Java: `Message.longHonk(title, message)` (`long` is a Java keyword) or `Message.error(...)`. */
        @JvmStatic @JvmName("longHonk")
        public fun long(title: String?, message: String): Builder = builder().long().title(title).message(message)

        @JvmStatic public fun blast(title: String?, message: String): Builder = builder().blast().title(title).message(message)

        @JvmStatic public fun info(title: String?, message: String): Builder = light(title, message)

        @JvmStatic public fun success(title: String?, message: String): Builder = beep(title, message)

        @JvmStatic public fun warning(title: String?, message: String): Builder = loud(title, message)

        @JvmStatic public fun error(title: String?, message: String): Builder = long(title, message)

        @JvmStatic public fun critical(title: String?, message: String): Builder = blast(title, message)
    }
}

/** Values applied when a message leaves these fields null or empty. */
public data class Defaults @JvmOverloads constructor(
    val source: String? = null,
    val environment: String? = null,
    val channel: String? = null,
)

/** The 202 answer: durably stored (which does not mean a push was delivered). */
public data class Accepted(
    /** Message id (`msg_…`); the original id when [duplicate] is true. */
    val id: String,
    /** This idempotency key was already accepted with the same payload in the last 24 hours. */
    val duplicate: Boolean,
    /** When the server accepted it (the first time, for a duplicate). */
    val receivedAt: Instant,
)

/** Exponential backoff with full jitter: attempt n waits random(0, min(maxMs, baseMs·2ⁿ)), or Retry-After when longer. */
public data class Backoff @JvmOverloads constructor(
    val baseMs: Long = 500,
    val maxMs: Long = 8_000,
)

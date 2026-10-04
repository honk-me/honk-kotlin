package app.honkme

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.random.Random

/**
 * Client for `POST /v1/messages`. Create one per process and reuse it (connections are kept
 * alive); [close] it on shutdown.
 *
 * Kotlin: `honk.loud("Disk 91%", "/var on app-01") { groupKey("disk/app-01/var") }` (suspend).
 * Java: `honk.sendBlocking(Message.loud("Disk 91%", "/var on app-01").groupKey("disk/app-01/var").build())`
 * or `honk.sendAsync(...)` for a `CompletableFuture`.
 *
 * The ingestion key is a server-side secret: never ship it inside an Android or desktop app.
 */
public class Honk @JvmOverloads constructor(
    /** Base address of your Honk server, e.g. `https://honk.example.com`. */
    url: String,
    /** A project ingestion key (`honk_…`). Keep it on the server. */
    key: String,
    /** Timeout of one HTTP attempt. */
    public val timeoutMs: Long = 5_000,
    /** Retries after the first attempt (network errors, 429 and 5xx only). */
    public val retries: Int = 4,
    /** Total time budget of one send, waits included. */
    public val deadlineMs: Long = 30_000,
    /** Source / environment / channel for messages that leave them unset. */
    public val defaults: Defaults = Defaults(),
    /** Check messages locally before sending (the server always validates). */
    public val validate: Boolean = true,
    public val backoff: Backoff = Backoff(),
    /** A custom HttpClient (proxies, executors). Configure it never to follow redirects. */
    httpClient: HttpClient? = null,
    /** Appended to the User-Agent header. */
    userAgent: String? = null,
) : AutoCloseable {
    /** Normalised base URL. */
    public val url: String
    private val key: String
    private val http: HttpClient
    private val userAgent: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("honk"))

    init {
        var base = url.trim().trimEnd('/')
        base = base.removeSuffix("/v1/messages")
        require(base.isNotEmpty()) { "Honk: url is required (the base address of your Honk server, e.g. https://honk.example.com; is HONK_URL set?)" }
        require(Regex("^https?://[^/]+.*", RegexOption.IGNORE_CASE).matches(base)) { "Honk: url must start with https:// (got \"$url\")" }
        val k = key.trim()
        require(k.isNotEmpty()) { "Honk: key is required (a project ingestion key honk_…; is HONK_KEY set?)" }
        require(k.startsWith("honk_") && k.all { it.code in 0x21..0x7E }) {
            "Honk: key must be a project ingestion key starting with honk_ (create one under Project → Keys)"
        }
        require(timeoutMs > 0 && deadlineMs > 0 && retries >= 0 && backoff.baseMs > 0 && backoff.maxMs > 0) {
            "Honk: timeoutMs, deadlineMs and backoff must be positive, retries ≥ 0"
        }
        this.url = base
        this.key = k
        this.userAgent = "honk-me-kotlin/$VERSION" + (userAgent?.let { " $it" } ?: "")
        this.http = httpClient ?: HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build()
    }

    /**
     * Sends one event and returns once Honk has durably stored it (202), which does not mean a
     * push was delivered. Network errors, timeouts, 429 and 5xx are retried with the same
     * Idempotency-Key until [retries] or [deadlineMs] runs out.
     *
     * @param idempotencyKey a stable key for this event (1–128 printable ASCII characters);
     *   default: the message's, else a new UUIDv7. Reused on every retry.
     * @throws HonkException (or a subclass); coroutine cancellation propagates as usual.
     */
    public suspend fun send(message: Message, idempotencyKey: String? = null): Accepted {
        val body = Wire.encode(message, defaults, validate)
        val key = idempotencyKey ?: message.idempotencyKey ?: UuidV7.generate()
        if (!Wire.validIdempotencyKey(key)) {
            throw HonkValidationException.local(
                listOf(FieldError("Idempotency-Key", "invalid_format", "use 1-128 printable ASCII characters without spaces, e.g. \"request-4812\"")),
            )
        }
        val target = URI.create("$url/v1/messages")
        val deadline = System.nanoTime() + deadlineMs * 1_000_000
        var attempt = 0
        while (true) {
            attempt++
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            val attemptMs = remainingMs.coerceIn(1, timeoutMs)
            val request = HttpRequest.newBuilder(target)
                .version(if (url.startsWith("http://", ignoreCase = true)) HttpClient.Version.HTTP_1_1 else HttpClient.Version.HTTP_2)
                .timeout(Duration.ofMillis(attemptMs))
                .header("Authorization", "Bearer ${this.key}")
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val failure: HonkException = try {
                val response = withTimeoutOrNull(attemptMs) { http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await() }
                when {
                    response == null -> timeout(attemptMs, key, attempt, null)
                    response.statusCode() in 200..299 -> return accepted(response, key, attempt)
                    else -> errorFor(response, key, attempt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpTimeoutException) {
                timeout(attemptMs, key, attempt, e)
            } catch (e: IOException) {
                HonkNetworkException("Could not reach Honk: ${e.message ?: e.javaClass.simpleName}", "network_error", key, attempt, e)
            }
            if (!failure.isRetryable || attempt > retries) throw failure
            val ceiling = minOf(backoff.maxMs, backoff.baseMs shl minOf(attempt - 1, 30))
            val waitMs = maxOf(Random.nextLong(0, ceiling + 1), failure.retryAfter?.toMillis() ?: 0)
            if (System.nanoTime() + waitMs * 1_000_000 >= deadline) throw failure
            delay(waitMs)
        }
    }

    /** [send] as a `CompletableFuture`, for Java. Cancelling the future cancels the send. */
    @JvmOverloads
    public fun sendAsync(message: Message, idempotencyKey: String? = null): CompletableFuture<Accepted> =
        scope.future { send(message, idempotencyKey) }

    /** [send], blocking the calling thread, for Java. Throws [HonkException] subclasses directly. */
    @JvmOverloads
    public fun sendBlocking(message: Message, idempotencyKey: String? = null): Accepted {
        try {
            return sendAsync(message, idempotencyKey).get()
        } catch (e: ExecutionException) {
            throw (e.cause as? RuntimeException) ?: e
        } catch (e: CompletionException) {
            throw (e.cause as? RuntimeException) ?: e
        }
    }

    /** A problem for [groupKey] (opens or continues its incident); severity defaults to LONG (error). */
    public suspend fun problem(groupKey: String, title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        send(Message.problem(groupKey, title, message).apply(configure).groupKey(groupKey).eventType(EventType.PROBLEM).build())

    /** A recovery for [groupKey] (closes its open incident); severity defaults to BEEP (success). */
    public suspend fun recovery(groupKey: String, title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        send(Message.recovery(groupKey, title, message).apply(configure).groupKey(groupKey).eventType(EventType.RECOVERY).build())

    /** A light honk (info). */
    public suspend fun light(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        withSeverity(Severity.INFO, title, message, configure)

    /** A beep-beep (success). */
    public suspend fun beep(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        withSeverity(Severity.SUCCESS, title, message, configure)

    /** A loud honk (warning). */
    public suspend fun loud(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        withSeverity(Severity.WARNING, title, message, configure)

    /** A long honk (error; pushes at least as high priority). */
    public suspend fun long(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        withSeverity(Severity.ERROR, title, message, configure)

    /** A blast (critical; pushes at least as high priority). */
    public suspend fun blast(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        withSeverity(Severity.CRITICAL, title, message, configure)

    /** Synonym of [light]. */
    public suspend fun info(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        light(title, message, configure)

    /** Synonym of [beep]. */
    public suspend fun success(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        beep(title, message, configure)

    /** Synonym of [loud]. */
    public suspend fun warning(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        loud(title, message, configure)

    /** Synonym of [long]. */
    public suspend fun error(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        long(title, message, configure)

    /** Synonym of [blast]. */
    public suspend fun critical(title: String?, message: String, configure: Message.Builder.() -> Unit = {}): Accepted =
        blast(title, message, configure)

    private suspend fun withSeverity(severity: Severity, title: String?, message: String, configure: Message.Builder.() -> Unit): Accepted =
        send(Message.builder().title(title).message(message).apply(configure).severity(severity).build())

    /** Cancels pending [sendAsync] calls. The client cannot be used afterwards for async sends. */
    override fun close() {
        scope.cancel()
    }

    public companion object {
        /** The SDK version, sent in the User-Agent (generated from VERSION_NAME in gradle.properties). */
        public const val VERSION: String = SDK_VERSION

        /** Reads `HONK_URL`, `HONK_KEY` and the optional `HONK_SOURCE`, `HONK_ENVIRONMENT`, `HONK_CHANNEL` defaults. */
        @JvmStatic
        @JvmOverloads
        public fun fromEnvironment(environment: Map<String, String> = System.getenv()): Honk = Honk(
            url = environment["HONK_URL"].orEmpty(),
            key = environment["HONK_KEY"].orEmpty(),
            defaults = Defaults(environment["HONK_SOURCE"], environment["HONK_ENVIRONMENT"], environment["HONK_CHANNEL"]),
        )

        /** A builder, for Java: `Honk.builder().url(url).key(key).timeoutMs(3000).build()`. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Delta-seconds or an HTTP date; null when absent or invalid. */
        @JvmStatic
        @JvmOverloads
        public fun parseRetryAfter(value: String?, now: Instant = Instant.now()): Duration? {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            v.toLongOrNull()?.let { return if (it >= 0) Duration.ofSeconds(it) else null }
            return try {
                val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                Duration.ofSeconds(maxOf(0, Duration.between(now, at).seconds))
            } catch (_: Exception) {
                null
            }
        }

        private fun timeout(ms: Long, key: String, attempt: Int, cause: Throwable?) = HonkTimeoutException(
            "Honk did not answer within $ms ms (the event may or may not have been stored; retrying with the same idempotency key is safe)",
            key,
            attempt,
            cause,
        )

        private fun accepted(response: HttpResponse<String>, key: String, attempts: Int): Accepted {
            val data = runCatching { Json.parse(response.body()) }.getOrNull() as? Map<*, *>
            val id = data?.get("id") as? String
                ?: throw HonkException("Honk answered ${response.statusCode()} without a message id", response.statusCode(), null, null, key, attempts)
            val received = (data["received_at"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
            return Accepted(id, data["duplicate"] == true, received)
        }

        internal fun errorFor(response: HttpResponse<String>, key: String, attempts: Int): HonkException {
            val error = (runCatching { Json.parse(response.body()) }.getOrNull() as? Map<*, *>)?.get("error") as? Map<*, *>
            val status = response.statusCode()
            val code = error?.get("code") as? String
            val text = (error?.get("message") as? String) ?: response.body().take(200).trim().ifEmpty { "HTTP $status" }
            val requestId = (error?.get("request_id") as? String) ?: response.headers().firstValue("X-Request-ID").orElse(null)
            val retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null))
            val label = "Honk $status${code?.let { " $it" } ?: ""}: $text"
            return when (status) {
                400, 413, 415, 422 -> {
                    val fields = (error?.get("fields") as? List<*>).orEmpty().mapNotNull { f ->
                        val m = f as? Map<*, *> ?: return@mapNotNull null
                        val field = m["field"] as? String ?: return@mapNotNull null
                        FieldError(field, m["code"] as? String ?: "invalid", m["message"] as? String)
                    }
                    val list = fields.joinToString("; ") { "${it.field} ${it.message ?: it.code}" }
                    HonkValidationException(if (list.isEmpty()) label else "$label ($list)", fields, false, status, code, requestId, key, attempts)
                }
                401, 403 -> HonkAuthException(label, status, code, requestId, key, attempts)
                409 -> HonkConflictException(label, status, code, requestId, key, attempts)
                429 -> HonkQuotaException(label, status, code, requestId, key, attempts, retryAfter)
                in 500..599 -> HonkServerException(label, status, code, requestId, key, attempts, retryAfter)
                in 300..399 -> HonkException(
                    "Honk answered $status redirect" + (response.headers().firstValue("Location").map { " to $it" }.orElse("")) +
                        "; set url to the final https address",
                    status, code, requestId, key, attempts,
                )
                404 -> HonkException("$label (is url the base address of your Honk server?)", status, code, requestId, key, attempts)
                else -> HonkException(label, status, code, requestId, key, attempts)
            }
        }
    }

    /** Java-friendly builder for [Honk]. */
    public class Builder internal constructor() {
        private var url: String = ""
        private var key: String = ""
        private var timeoutMs: Long = 5_000
        private var retries: Int = 4
        private var deadlineMs: Long = 30_000
        private var defaults: Defaults = Defaults()
        private var validate: Boolean = true
        private var backoff: Backoff = Backoff()
        private var httpClient: HttpClient? = null
        private var userAgent: String? = null

        public fun url(url: String): Builder = apply { this.url = url }
        public fun key(key: String): Builder = apply { this.key = key }
        public fun timeoutMs(timeoutMs: Long): Builder = apply { this.timeoutMs = timeoutMs }
        public fun retries(retries: Int): Builder = apply { this.retries = retries }
        public fun deadlineMs(deadlineMs: Long): Builder = apply { this.deadlineMs = deadlineMs }
        public fun defaults(defaults: Defaults): Builder = apply { this.defaults = defaults }
        public fun validate(validate: Boolean): Builder = apply { this.validate = validate }
        public fun backoff(backoff: Backoff): Builder = apply { this.backoff = backoff }
        public fun httpClient(httpClient: HttpClient?): Builder = apply { this.httpClient = httpClient }
        public fun userAgent(userAgent: String?): Builder = apply { this.userAgent = userAgent }
        public fun build(): Honk = Honk(url, key, timeoutMs, retries, deadlineMs, defaults, validate, backoff, httpClient, userAgent)
    }
}

package me.honk

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HonkTest {
    @Test
    fun `serialises every field with the openapi names`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val accepted = client(s.url).send(
                Message(
                    "Billing API could not connect to Redis after 3 attempts.",
                    title = "Redis connection failed", severity = Severity.LONG, priority = Priority.HIGH,
                    category = Category.INFRASTRUCTURE, source = "billing-api", environment = "production",
                    channel = "infrastructure", groupKey = "billing/redis/connectivity", eventType = EventType.PROBLEM,
                    occurredAt = Instant.parse("2026-10-01T21:10:00Z"), url = "https://example.com/incidents/redis",
                    imageUrl = "https://cdn.example.com/a.jpg?w=1&h=2",
                    metadata = mapOf("host" to "app-01", "attempts" to 3, "retried" to true, "ratio" to 0.5),
                    ttlSeconds = 3600, sourceSequence = 42,
                ),
            )
            assertEquals(Accepted("msg_01k6h3w4z5x6y7z8a9b0c1d2e3", false, Instant.parse("2026-10-02T21:10:00.123Z")), accepted)
            val r = s.requests.single()
            assertEquals("/v1/messages", r.path)
            assertEquals("Bearer $TEST_KEY", r.header("Authorization"))
            assertEquals("application/json", r.header("Content-Type"))
            assertEquals("honk-me-kotlin/${Honk.VERSION}", r.header("User-Agent"))
            assertTrue(UUID_V7.matches(r.header("Idempotency-Key")!!))
            assertEquals(
                """{"title":"Redis connection failed","message":"Billing API could not connect to Redis after 3 attempts.","severity":"error","priority":"high","category":"infrastructure","source":"billing-api","environment":"production","channel":"infrastructure","group_key":"billing/redis/connectivity","event_type":"problem","occurred_at":"2026-10-01T21:10:00.000Z","url":"https://example.com/incidents/redis","image_url":"https://cdn.example.com/a.jpg?w=1&h=2","metadata":{"host":"app-01","attempts":3,"retried":true,"ratio":0.5},"ttl_seconds":3600,"source_sequence":42}""",
                r.body,
            )
        }
    }

    @Test
    fun `minimal message and defaults`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val honk = client(s.url, defaults = Defaults("cron", "production", ""))
            honk.send(Message("a"))
            honk.send(Message("b", source = "laravel", channel = "requests"))
            assertEquals("""{"message":"a","source":"cron","environment":"production"}""", s.requests[0].body)
            assertEquals("""{"message":"b","source":"laravel","environment":"production","channel":"requests"}""", s.requests[1].body)
        }
    }

    @Test
    fun `helpers and the honk scale`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val honk = client(s.url)
            honk.light("a", "m")
            honk.beep("b", "m")
            honk.loud("c", "m") { groupKey("disk/var") }
            honk.long("d", "m")
            honk.blast("e", "m") { severity("light") }
            honk.problem("db/backup", "Backup failed", "exit 1") { idempotencyKey("p-1") }
            honk.recovery("db/backup", "Backup OK", "exit 0")
            honk.problem("q", "Queue", "failing") { severity(Severity.BLAST) }
            honk.warning(null, "synonym")
            val r = s.requests
            assertEquals(listOf("info", "success", "warning", "error", "critical", "error", "success", "critical", "warning"), r.map { it.json["severity"] })
            assertEquals("disk/var", r[2].json["group_key"])
            assertEquals("problem", r[5].json["event_type"])
            assertEquals("p-1", r[5].header("Idempotency-Key"))
            assertEquals("recovery", r[6].json["event_type"])
            assertNull(r[8].json["title"])
        }
        assertTrue(Severity.LOUD === Severity.WARNING && Severity.BLAST === Severity.CRITICAL)
        assertEquals(Severity.WARNING, Severity.parseOrNull(" LOUD "))
        assertNull(Severity.parseOrNull("fatal"))
        assertEquals("loud", Severity.WARNING.horn)
        assertFailsWith<HonkValidationException> { Severity.parse("fatal") }
    }

    @Test
    fun `retries reuse the idempotency key and body`() = runBlocking {
        MockServer(Step.error(503, "unavailable", "0"), Step.error(500, "internal"), Step.Drop, Step.accepted()).use { s ->
            client(s.url).send(Message("x"))
            assertEquals(4, s.requests.size)
            assertEquals(1, s.requests.map { it.header("Idempotency-Key") }.toSet().size)
            assertEquals(1, s.requests.map { it.body }.toSet().size)
        }
    }

    @Test
    fun `retries a timed-out attempt with the same key`() = runBlocking {
        MockServer(Step.Respond(202, "{}", delayMs = 600), Step.accepted()).use { s ->
            client(s.url, timeoutMs = 150).send(Message("x"), "k-1")
            assertEquals(listOf("k-1", "k-1"), s.requests.map { it.header("Idempotency-Key") })
        }
    }

    @Test
    fun `honours Retry-After`() = runBlocking {
        MockServer(Step.error(429, "rate_limited", "1"), Step.accepted()).use { s ->
            val start = System.nanoTime()
            client(s.url).send(Message("x"))
            assertTrue(System.nanoTime() - start >= 1_000_000_000)
        }
    }

    @Test
    fun `Retry-After beyond the deadline fails fast`() = runBlocking {
        MockServer(Step.error(429, "quota_exceeded", "7200", ""","limit":"messages_per_day"""")).use { s ->
            val start = System.nanoTime()
            val e = assertFailsWith<HonkQuotaException> { client(s.url).send(Message("x"), "q-1") }
            assertEquals("quota_exceeded", e.code)
            assertEquals(429, e.status)
            assertEquals(Duration.ofSeconds(7200), e.retryAfter)
            assertEquals(1, e.attempts)
            assertEquals("q-1", e.idempotencyKey)
            assertTrue(e.isRetryable)
            assertTrue(System.nanoTime() - start < 1_000_000_000)
            assertEquals(1, s.requests.size)
        }
    }

    @Test
    fun `gives up after retries`() = runBlocking {
        MockServer(Step.error(503, "unavailable", "0")).use { s ->
            val e = assertFailsWith<HonkServerException> { client(s.url, retries = 2).send(Message("x")) }
            assertEquals(3, e.attempts)
            assertEquals("req_test", e.requestId)
            assertEquals(3, s.requests.size)
        }
    }

    @Test
    fun `stops at the deadline`() = runBlocking {
        MockServer(Step.error(503, "unavailable", "1")).use { s ->
            val start = System.nanoTime()
            assertFailsWith<HonkServerException> { client(s.url, retries = 10, deadlineMs = 1_500).send(Message("x")) }
            assertTrue(System.nanoTime() - start < 1_600_000_000)
            assertEquals(2, s.requests.size)
        }
    }

    @Test
    fun `timeouts become HonkTimeoutException`() = runBlocking {
        MockServer(Step.Respond(202, "{}", delayMs = 500)).use { s ->
            val e = assertFailsWith<HonkTimeoutException> { client(s.url, timeoutMs = 50, retries = 1).send(Message("x")) }
            val network: HonkNetworkException = e // timeouts are network errors
            assertTrue(network.isRetryable)
            assertEquals("timeout", e.code)
            assertEquals(2, e.attempts)
        }
    }

    @Test
    fun `connection refused becomes HonkNetworkException`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<HonkNetworkException> { client("http://127.0.0.1:$port", retries = 1).send(Message("x")) }
        assertFalse(e is HonkTimeoutException)
        assertEquals("network_error", e.code)
        assertEquals(2, e.attempts)
        assertTrue(UUID_V7.matches(e.idempotencyKey!!))
    }

    @Test
    fun `coroutine cancellation propagates`() = runBlocking {
        MockServer(Step.Respond(202, "{}", delayMs = 2_000)).use { s ->
            assertFailsWith<TimeoutCancellationException> { withTimeout(100) { client(s.url).send(Message("x")) } }
        }
    }

    @Test
    fun `error mapping is never retried`() = runBlocking {
        val cases = listOf(
            Step.error(422, "validation_failed", extra = ""","fields":[{"field":"severity","code":"invalid_enum","message":"must be one of"}]""") to HonkValidationException::class,
            Step.error(413, "payload_too_large") to HonkValidationException::class,
            Step.error(401, "invalid_key") to HonkAuthException::class,
            Step.error(403, "priority_not_allowed") to HonkAuthException::class,
            Step.error(409, "idempotency_conflict") to HonkConflictException::class,
            Step.error(404, "not_found") to HonkException::class,
        )
        for ((step, type) in cases) {
            MockServer(step).use { s ->
                val e = assertFailsWith<HonkException> { client(s.url).send(Message("x")) }
                assertEquals(type, e::class, "${step.status}")
                assertEquals(step.status, e.status)
                assertEquals("req_test", e.requestId)
                assertEquals(1, e.attempts)
                assertFalse(e.isRetryable)
                if (e is HonkValidationException) assertFalse(e.isLocal)
                assertEquals(1, s.requests.size)
            }
        }
    }

    @Test
    fun `validation errors expose fields`() = runBlocking {
        MockServer(Step.error(422, "validation_failed", extra = ""","fields":[{"field":"image_url","code":"invalid_format","message":"must be https"}]""")).use { s ->
            val e = assertFailsWith<HonkValidationException> { client(s.url).send(Message("x")) }
            assertEquals(listOf(FieldError("image_url", "invalid_format", "must be https")), e.fields)
            assertTrue(e.message!!.contains("image_url must be https"))
        }
    }

    @Test
    fun `proxy html error is retried then reported`() = runBlocking {
        MockServer(Step.Respond(502, "<html>Bad Gateway</html>")).use { s ->
            val e = assertFailsWith<HonkServerException> { client(s.url, retries = 1).send(Message("x")) }
            assertEquals(502, e.status)
            assertNull(e.code)
            assertEquals(2, s.requests.size)
        }
    }

    @Test
    fun `redirects are reported, not followed`() = runBlocking {
        MockServer(Step.Respond(301, "", mapOf("Location" to "https://honk.example.com/v1/messages"))).use { s ->
            val e = assertFailsWith<HonkException> { client(s.url).send(Message("x")) }
            assertTrue(e.message!!.contains("redirect to https://honk.example.com"))
            assertEquals(1, s.requests.size)
        }
    }

    @Test
    fun `duplicate and explicit key`() = runBlocking {
        MockServer(Step.accepted(duplicate = true)).use { s ->
            assertTrue(client(s.url).send(Message("x"), "deploy-4812").duplicate)
            assertEquals("deploy-4812", s.requests[0].header("Idempotency-Key"))
        }
    }

    @Test
    fun `validate false leaves value checks to the server`() = runBlocking {
        MockServer(Step.error(422, "validation_failed")).use { s ->
            assertFailsWith<HonkValidationException> { client(s.url, validate = false).send(Message("x", ttlSeconds = 5)) }
            assertEquals(5L, s.requests[0].json["ttl_seconds"])
        }
    }

    @Test
    fun `sendAsync and sendBlocking`() {
        MockServer(Step.accepted(), Step.error(401, "invalid_key")).use { s ->
            val honk = client(s.url)
            assertEquals("msg_01k6h3w4z5x6y7z8a9b0c1d2e3", honk.sendAsync(Message("x")).get().id)
            assertFailsWith<HonkAuthException> { honk.sendBlocking(Message("y")) }
            honk.close()
        }
    }

    @Test
    fun `concurrent sends share one client`() = runBlocking {
        MockServer(Step.accepted()).use { s ->
            val honk = client(s.url)
            (1..20).map { async { honk.light("t$it", "m") } }.forEach { it.await() }
            assertEquals(20, s.requests.size)
            delay(1)
        }
    }

    @Test
    fun `construction rejects missing or malformed url and key`() {
        val cases = listOf(
            Triple("", TEST_KEY, "HONK_URL"), Triple("honk.example.com", TEST_KEY, "https://"),
            Triple("https://h", "", "HONK_KEY"), Triple("https://h", "hka_mobile", "honk_"),
        )
        for ((url, key, needle) in cases) {
            val e = assertFailsWith<IllegalArgumentException> { Honk(url, key) }
            assertTrue(e.message!!.contains(needle), e.message)
        }
        assertEquals("https://honk.example.com", Honk("https://honk.example.com/v1/messages/", TEST_KEY).url)
    }

    @Test
    fun `fromEnvironment and UUIDv7 and Retry-After`() {
        val honk = Honk.fromEnvironment(mapOf("HONK_URL" to "https://honk.example.com", "HONK_KEY" to TEST_KEY, "HONK_SOURCE" to "cron"))
        assertEquals(Defaults("cron"), honk.defaults)

        val a = UuidV7.generate(1_700_000_000_000)
        val b = UuidV7.generate(1_700_000_000_001)
        assertTrue(UUID_V7.matches(a) && a < b)
        assertEquals("018bcfe56800", a.take(13).replace("-", ""))

        val now = Instant.parse("2026-10-02T10:00:00Z")
        assertEquals(Duration.ofSeconds(3), Honk.parseRetryAfter("3"))
        assertEquals(Duration.ofSeconds(120), Honk.parseRetryAfter(" 120 "))
        assertNull(Honk.parseRetryAfter(null))
        assertNull(Honk.parseRetryAfter("soon"))
        assertEquals(Duration.ofSeconds(30), Honk.parseRetryAfter("Fri, 2 Oct 2026 10:00:30 GMT", now))
        assertEquals(Duration.ZERO, Honk.parseRetryAfter("Fri, 2 Oct 2026 09:00:00 GMT", now))
    }
}

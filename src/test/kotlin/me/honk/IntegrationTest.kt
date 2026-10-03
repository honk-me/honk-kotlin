package me.honk

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs against a real Honk server: `HONK_URL=… HONK_KEY=… ./gradlew integrationTest` (excluded otherwise). */
@Tag("integration")
class IntegrationTest {
    private val env = System.getenv()
    private val run = "kotlin-${UuidV7.generate()}"

    private fun client(key: String? = null, validate: Boolean = true) =
        Honk(env.getValue("HONK_URL"), key ?: env.getValue("HONK_KEY"), defaults = Defaults("sdk-kotlin-it", "test"), validate = validate)

    @Test
    fun `minimal message is accepted`() = runBlocking {
        val accepted = client().send(Message("minimal $run"))
        assertTrue(accepted.id.startsWith("msg_") && !accepted.duplicate)
    }

    @Test
    fun `every field and a replay is a duplicate`() = runBlocking {
        val honk = client()
        val message = Message(
            "Ana (Acme) asked for a quote:\nonline shop, 40 products", title = "Customer request $run", severity = Severity.LIGHT,
            priority = Priority.HIGH, category = Category.CUSTOMERS, source = "sdk-kotlin-it", environment = "test", channel = "requests",
            groupKey = "requests/$run", eventType = EventType.EVENT, occurredAt = Instant.now(), url = "https://example.com/admin/requests/4812",
            imageUrl = "https://example.com/images/quote.png", metadata = mapOf("request_id" to "4812", "amount" to 1250.5, "vip" to true),
            ttlSeconds = 600, sourceSequence = 1,
        )
        val key = "it-${UuidV7.generate()}"
        val first = honk.send(message, key)
        val again = honk.send(message, key)
        assertTrue(!first.duplicate && again.duplicate && again.id == first.id)
    }

    @Test
    fun `same key different payload is a conflict`() = runBlocking {
        val honk = client()
        val key = "it-${UuidV7.generate()}"
        honk.light("first", "payload A $run") { idempotencyKey(key) }
        val e = assertFailsWith<HonkConflictException> { honk.light("first", "payload B $run") { idempotencyKey(key) } }
        assertEquals(409, e.status)
        assertEquals("idempotency_conflict", e.code)
        assertEquals(key, e.idempotencyKey)
    }

    @Test
    fun `problem then recovery`() = runBlocking {
        val honk = client()
        val p = honk.problem("it/kotlin/$run", "Backup failed", "pg_dump exited with 1") { sourceSequence(1) }
        val r = honk.recovery("it/kotlin/$run", "Backup OK", "pg_dump finished") { sourceSequence(2) }
        assertNotEquals(p.id, r.id)
    }

    @Test
    fun `horn alias and canonical severity are the same event`() = runBlocking {
        val honk = client()
        val key = "it-${UuidV7.generate()}"
        val first = honk.loud("Disk 91%", "/var on app-01 $run") { idempotencyKey(key) }
        val again = honk.send(Message.builder().title("Disk 91%").message("/var on app-01 $run").severity("WARNING").build(), key)
        assertTrue(again.duplicate && again.id == first.id)
    }

    @Test
    fun `wrong key is an auth error`() = runBlocking {
        val e = assertFailsWith<HonkAuthException> { client("honk_000000000000_00000000000000000000000000000000").send(Message("x")) }
        assertEquals(401, e.status)
        assertEquals("invalid_key", e.code)
    }

    @Test
    fun `urgent without allow urgent`() = runBlocking {
        try {
            assertTrue(client().send(Message("urgent $run", priority = Priority.URGENT)).id.startsWith("msg_")) // the key allows urgent
        } catch (e: HonkAuthException) {
            assertEquals(403, e.status)
            assertEquals("priority_not_allowed", e.code)
        }
    }

    @Test
    fun `server side validation maps fields`() = runBlocking {
        val e = assertFailsWith<HonkValidationException> { client(validate = false).send(Message("x", ttlSeconds = 5)) }
        assertTrue(!e.isLocal && e.status == 422)
        assertEquals(listOf("ttl_seconds:out_of_range"), e.fields.map { "${it.field}:${it.code}" })
    }

    @Test
    fun `java style blocking send`() {
        client().use { honk ->
            val accepted = honk.sendBlocking(Message.beep("Java interop $run", "sendBlocking").groupKey("it/kotlin/java").build())
            if (!accepted.id.startsWith("msg_")) fail("unexpected id ${accepted.id}")
        }
    }
}

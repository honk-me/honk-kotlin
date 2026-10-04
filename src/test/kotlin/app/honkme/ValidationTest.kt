package app.honkme

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValidationTest {
    companion object {
        @JvmStatic
        fun cases(): List<Arguments> {
            val many = (0 until 17).associate { "k$it" to it }
            val big = (0 until 16).associate { "k$it" to "\"".repeat(500) }
            return listOf(
                Arguments.of(Message(""), "message", "required"),
                Arguments.of(Message("   "), "message", "too_short"),
                Arguments.of(Message("x".repeat(8193)), "message", "too_long"),
                Arguments.of(Message("é".repeat(4097)), "message", "too_long"),
                Arguments.of(Message("bell\u0007"), "message", "invalid_format"),
                Arguments.of(Message("x", title = "t".repeat(161)), "title", "too_long"),
                Arguments.of(Message("x", title = "two\nlines"), "title", "invalid_format"),
                Arguments.of(Message("x", title = "  "), "title", "too_short"),
                Arguments.of(Message("x", environment = "e".repeat(33)), "environment", "too_long"),
                Arguments.of(Message("x", groupKey = "g".repeat(129)), "group_key", "too_long"),
                Arguments.of(Message("x", eventType = EventType.RECOVERY), "group_key", "requires_group_key"),
                Arguments.of(Message("x", sourceSequence = 3), "source_sequence", "requires_group_key"),
                Arguments.of(Message("x", groupKey = "g", sourceSequence = -1), "source_sequence", "out_of_range"),
                Arguments.of(Message("x", groupKey = "g", sourceSequence = 1L shl 53), "source_sequence", "out_of_range"),
                Arguments.of(Message("x", url = "http://example.com"), "url", "invalid_format"),
                Arguments.of(Message("x", url = "https://user:pw@example.com"), "url", "invalid_format"),
                Arguments.of(Message("x", url = "https://example.com/" + "a".repeat(2048)), "url", "invalid_format"),
                Arguments.of(Message("x", imageUrl = "https://cdn.example.com/a.jpg#x"), "image_url", "invalid_format"),
                Arguments.of(Message("x", imageUrl = "https://cdn.example.com:99999/a.jpg"), "image_url", "invalid_format"),
                Arguments.of(Message("x", metadata = mapOf("nested" to mapOf("a" to 1))), "metadata.nested", "invalid_format"),
                Arguments.of(Message("x", metadata = mapOf("bad key" to 1)), "metadata.bad key", "invalid_format"),
                Arguments.of(Message("x", metadata = mapOf("v" to "v".repeat(513))), "metadata.v", "invalid_format"),
                Arguments.of(Message("x", metadata = mapOf("n" to Double.NaN)), "metadata.n", "invalid_format"),
                Arguments.of(Message("x", metadata = many), "metadata", "too_long"),
                Arguments.of(Message("x", ttlSeconds = 59), "ttl_seconds", "out_of_range"),
                Arguments.of(Message("x", ttlSeconds = 86401), "ttl_seconds", "out_of_range"),
                Arguments.of(Message("x".repeat(8000), metadata = big), "body", "too_long"),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `rejects locally`(message: Message, field: String, code: String) {
        val e = assertFailsWith<HonkValidationException> { Wire.encode(message, Defaults(), true) }
        assertTrue(e.isLocal)
        assertEquals(0, e.attempts)
        assertTrue(e.fields.any { it.field == field && it.code == code }, "want $field/$code, got ${e.fields}")
    }

    @Test
    fun `reports every error at once`() {
        val e = assertFailsWith<HonkValidationException> { Wire.encode(Message("", url = "ftp://a"), Defaults(), true) }
        assertEquals(listOf("message", "url"), e.fields.map { it.field })
    }

    @Test
    fun `valid edge cases`() {
        val body = Wire.encode(
            Message(
                "line1\nline2\ttab\r\n", title = "t".repeat(160), groupKey = "g", url = "https://[::1]:8443/path?q=1#frag",
                imageUrl = "HTTPS://cdn.example.com/a.jpg?size=2", metadata = mapOf("a.b-c_d" to "v", "n" to 1.5, "b" to false),
                ttlSeconds = 60, sourceSequence = (1L shl 53) - 1,
            ),
            Defaults(),
            true,
        )
        assertTrue(body.contains("\"source_sequence\":9007199254740991"))
        assertTrue(body.contains("\"message\":\"line1\\nline2\\ttab\\r\\n\""))
    }

    @Test
    fun `builder and invalid idempotency keys`() = runBlocking {
        val m = Message.builder().title("New request").line("Customer: Ana").line("Company: Acme").loud().meta("request_id", "1").build()
        assertEquals(Message("Customer: Ana\nCompany: Acme", title = "New request", severity = Severity.WARNING, metadata = mapOf("request_id" to "1")), m)
        assertFailsWith<HonkValidationException> { Message.builder().title("no text").build() }
        assertEquals(Severity.CRITICAL, Message.builder().message("x").severity("BLAST").build().severity)

        val honk = Honk("https://honk.example.com", TEST_KEY)
        for (key in listOf("", "has space", "x".repeat(129), "ünicode")) {
            val e = assertFailsWith<HonkValidationException> { honk.send(Message("x"), key) }
            assertEquals("Idempotency-Key", e.fields.first().field)
        }
    }

    @Test
    fun `json round trip`() {
        val text = Json.write(mapOf("s" to "a\"b\\c\n\u0001é", "n" to 1, "d" to 1.25, "b" to true, "z" to null, "l" to listOf(1, "x")))
        assertEquals("""{"s":"a\"b\\c\n\u0001é","n":1,"d":1.25,"b":true,"z":null,"l":[1,"x"]}""", text)
        assertEquals(mapOf("s" to "a\"b\\c\n\u0001é", "n" to 1L, "d" to 1.25, "b" to true, "z" to null, "l" to listOf(1L, "x")), Json.parse(text))
        assertEquals(mapOf("u" to "é/"), Json.parse("""{ "u" : "é\/" }"""))
    }
}

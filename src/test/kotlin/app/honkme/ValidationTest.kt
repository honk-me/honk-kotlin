package app.honkme

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
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
            val call = Action("Call", "tel:+15550134")
            fun action(title: String, url: String) = Message("x", actions = listOf(Action(title, url)))
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
                Arguments.of(Message("x", actions = listOf(call, call, call, call)), "actions", "too_long"),
                Arguments.of(action("  ", "tel:1"), "actions[0].title", "required"),
                Arguments.of(action("t".repeat(41), "tel:1"), "actions[0].title", "too_long"),
                Arguments.of(action("Call\nEmily", "tel:1"), "actions[0].title", "invalid_format"),
                Arguments.of(action("Call\u0007", "tel:1"), "actions[0].title", "invalid_format"),
                Arguments.of(action("Open", ""), "actions[0].url", "required"),
                Arguments.of(action("Open", "https://example.com/" + "a".repeat(2029)), "actions[0].url", "too_long"),
                Arguments.of(action("Open", "http://example.com"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Open", "javascript:alert(1)"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Open", "shop://orders/42"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Open", "https://user:pw@example.com"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Open", "https:///orders"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Reply", "mailto:emily@example.com?subject=Your quote"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Reply", "mailto:?subject=Hi"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Reply", "mailto:emily@localhost"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Reply", "mailto:emily@example.com,bob@example.com"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Reply", "mailto:Emily%20%3Cemily@example.com%3E"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Reply", "mailto:emily..carter@example.com"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Reply", "mailto:emily@example.com?cc=boss@example.com"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Reply", "mailto:emily@example.com?subject=100%"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Call", "tel:+1\u00A05550134"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Call", "tel:+1-555-CALL"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Call", "tel:+"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Call", "tel:+15550134?x=1"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Text", "sms://+15550134"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Text", "sms:+15550134?subject=Hi"), "actions[0].url", "invalid_format"),
                Arguments.of(action("Text", "sms:+15550134?body=a;b"), "actions[0].url", "invalid_format"),
                Arguments.of(Message("x", actions = listOf(call, Action("Open", "ftp://example.com"))), "actions[1].url", "invalid_format"),
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
    fun `action errors are reported with the others`() {
        val e = assertFailsWith<HonkValidationException> {
            Wire.encode(Message("", url = "ftp://a", actions = listOf(Action("", "ftp://b"), Action("Call", "tel:1")), ttlSeconds = 5), Defaults(), true)
        }
        assertEquals(
            listOf("message:required", "url:invalid_format", "actions[0].title:required", "actions[0].url:invalid_format", "ttl_seconds:out_of_range"),
            e.fields.map { "${it.field}:${it.code}" },
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://shop.example.com/admin/orders/42?tab=notes#latest", "HTTPS://example.com:8443/a",
            "mailto:emily@example.com", "MailTo:emily@example.com?subject=Your%20quote&body=Hi%20Emily",
            "mailto:first.last+quotes@example.co.uk?subject=Your+quote", "mailto:emily%40example.com", "mailto:ana@[192.0.2.1]",
            "tel:+15550134", "tel:+1-555-013.4", "tel:(555)0134", "TEL://+15550134",
            "sms:+15550134", "SMS:5550134?body=On%20my%20way", "sms:+15550134?",
        ],
    )
    fun `valid action urls`(url: String) {
        val body = Wire.encode(Message("x", actions = listOf(Action("Open", url))), Defaults(), true)
        assertEquals(listOf(mapOf("title" to "Open", "url" to url)), (Json.parse(body) as Map<*, *>)["actions"])
    }

    @Test
    fun `valid action titles and the longest url`() {
        val actions = listOf(
            Action("é".repeat(40), "tel:1"),
            Action("  Call Emily Carter  ", "tel:1"),
            Action("Open", "https://example.com/" + "a".repeat(2028)),
        )
        Wire.encode(Message("x", actions = actions), Defaults(), true)
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
        val reply = Action("Reply", "mailto:emily@example.com?subject=Your%20quote")
        val withActions = Message.builder().message("Emily asked for a quote").action(reply.title, reply.url).actions(listOf(Action("Call", "tel:+15550134"))).build()
        assertEquals(listOf(reply, Action("Call", "tel:+15550134")), withActions.actions)
        assertEquals(withActions, withActions.toBuilder().build())
        assertEquals(3, withActions.toBuilder().action("Open", "https://example.com").build().actions.size)
        assertEquals(2, withActions.actions.size) // the builder copies, never shares
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

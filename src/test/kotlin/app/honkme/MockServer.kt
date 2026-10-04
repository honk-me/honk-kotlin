package app.honkme

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/** A scripted answer: a response (optionally delayed) or a dropped connection. */
sealed interface Step {
    data class Respond(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap(), val delayMs: Long = 0) : Step
    data object Drop : Step

    companion object {
        fun accepted(duplicate: Boolean = false) = Respond(
            202,
            """{"id":"msg_01k6h3w4z5x6y7z8a9b0c1d2e3","status":"accepted","duplicate":$duplicate,"received_at":"2026-10-02T21:10:00.123Z"}""",
        )

        fun error(status: Int, code: String, retryAfter: String? = null, extra: String = "") = Respond(
            status,
            """{"error":{"code":"$code","message":"$code happened","request_id":"req_test"$extra}}""",
            retryAfter?.let { mapOf("Retry-After" to it) } ?: emptyMap(),
        )
    }
}

data class Recorded(val path: String, val headers: Map<String, String>, val body: String, val atNanos: Long) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    @Suppress("UNCHECKED_CAST")
    val json: Map<String, Any?> get() = Json.parse(body) as Map<String, Any?>
}

/** JDK HttpServer answering each request with the next step (the last one repeats). */
class MockServer(vararg steps: Step) : AutoCloseable {
    private val script = steps.toList().ifEmpty { listOf(Step.accepted()) }
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            val headers = exchange.requestHeaders.entries.associate { it.key to it.value.joinToString(",") }
            val n = synchronized(requests) {
                requests += Recorded(exchange.requestURI.path, headers, body, System.nanoTime())
                requests.size - 1
            }
            when (val step = script[minOf(n, script.size - 1)]) {
                Step.Drop -> exchange.close()
                is Step.Respond -> {
                    if (step.delayMs > 0) Thread.sleep(step.delayMs)
                    runCatching {
                        step.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        val bytes = step.body.encodeToByteArray()
                        exchange.sendResponseHeaders(step.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                    }
                    exchange.close()
                }
            }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
    }

    override fun close() = server.stop(0)
}

const val TEST_KEY = "honk_ab12cd34ef56_0123456789abcdefghijABCDEFGHIJ0123"
val UUID_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

fun client(
    url: String,
    timeoutMs: Long = 5_000,
    retries: Int = 4,
    deadlineMs: Long = 30_000,
    defaults: Defaults = Defaults(),
    validate: Boolean = true,
) = Honk(url, TEST_KEY, timeoutMs, retries, deadlineMs, defaults, validate, Backoff(1, 5))

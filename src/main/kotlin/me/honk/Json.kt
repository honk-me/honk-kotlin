package me.honk

import java.math.BigDecimal
import java.math.BigInteger

/**
 * A deliberately tiny JSON writer and reader. The request body is a flat object of strings,
 * numbers and booleans (plus one flat metadata object) and the answers are two small fixed
 * shapes, so a hand-written codec keeps the library free of a serialization framework and its
 * compiler plugin, for Kotlin and Java users alike.
 */
internal object Json {
    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte, is BigInteger -> out.append(value.toString())
            is BigDecimal -> out.append(value.toPlainString())
            is Double -> out.append(number(value))
            is Float -> out.append(number(value.toDouble()))
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    string(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> string(out, value.toString())
        }
    }

    private fun number(d: Double): String {
        require(d.isFinite()) { "JSON numbers must be finite" }
        return if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    /** Parses a JSON document into Map / List / String / Long / Double / Boolean / null. */
    fun parse(text: String): Any? = Reader(text).run {
        val v = value()
        skipWhitespace()
        if (pos != text.length) throw IllegalArgumentException("trailing data at $pos")
        v
    }

    private class Reader(val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        fun value(): Any? {
            skipWhitespace()
            require(pos < s.length) { "unexpected end" }
            return when (s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "invalid literal at $pos" }
            pos += word.length
            return value
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipWhitespace()
            if (s.getOrNull(pos) == '}') return out.also { pos++ }
            while (true) {
                skipWhitespace()
                require(s.getOrNull(pos) == '"') { "expected a key at $pos" }
                val key = string()
                skipWhitespace()
                require(s.getOrNull(pos) == ':') { "expected ':' at $pos" }
                pos++
                out[key] = value()
                skipWhitespace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> return out.also { pos++ }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipWhitespace()
            if (s.getOrNull(pos) == ']') return out.also { pos++ }
            while (true) {
                out.add(value())
                skipWhitespace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> return out.also { pos++ }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        fun string(): String {
            val out = StringBuilder()
            pos++
            while (true) {
                require(pos < s.length) { "unterminated string" }
                when (val c = s[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("invalid escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        fun number(): Any {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            val text = s.substring(start, pos)
            require(text.isNotEmpty()) { "unexpected character at $start" }
            return text.toLongOrNull() ?: text.toDouble()
        }
    }
}

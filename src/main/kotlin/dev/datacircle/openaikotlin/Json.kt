package dev.datacircle.openaikotlin

import java.math.BigDecimal

/**
 * A small JSON reader, so that the tools add no dependency to the app's own openai-kotlin.
 *
 * An object is a [Map], an array a [List], a number a [BigDecimal], then [String], [Boolean] and `null`.
 */
internal class Json private constructor(private val text: String) {

    private var at = 0

    companion object {
        fun parse(text: String): Any? {
            val json = Json(text)
            json.skipSpace()
            val value = json.value()
            json.skipSpace()
            if (json.at != text.length) {
                throw json.error("text after the value")
            }
            return value
        }
    }

    private fun value(): Any? {
        if (at >= text.length) {
            throw error("the end of the text")
        }
        return when (val c = text[at]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c in '0'..'9') number() else throw error("'$c'")
        }
    }

    private fun obj(): Map<String, Any?> {
        val obj = LinkedHashMap<String, Any?>()
        at++
        skipSpace()
        if (next('}')) {
            return obj
        }
        while (true) {
            skipSpace()
            if (at >= text.length || text[at] != '"') {
                throw error("no key")
            }
            val key = string()
            skipSpace()
            expect(':')
            skipSpace()
            obj[key] = value()
            skipSpace()
            if (next(',')) {
                continue
            }
            expect('}')
            return obj
        }
    }

    private fun array(): List<Any?> {
        val array = ArrayList<Any?>()
        at++
        skipSpace()
        if (next(']')) {
            return array
        }
        while (true) {
            skipSpace()
            array.add(value())
            skipSpace()
            if (next(',')) {
                continue
            }
            expect(']')
            return array
        }
    }

    private fun string(): String {
        at++
        val out = StringBuilder()
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') {
                return out.toString()
            }
            if (c != '\\') {
                out.append(c)
                continue
            }
            if (at >= text.length) {
                break
            }
            when (val escaped = text[at++]) {
                '"', '\\', '/' -> out.append(escaped)
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (at + 4 > text.length) {
                        throw error("a short \\u escape")
                    }
                    val code = text.substring(at, at + 4).toIntOrNull(16) ?: throw error("a bad \\u escape")
                    out.append(code.toChar())
                    at += 4
                }
                else -> throw error("a bad escape")
            }
        }
        throw error("a string with no end")
    }

    private fun number(): BigDecimal {
        val start = at
        while (at < text.length && text[at] in "+-0123456789.eE") {
            at++
        }
        return text.substring(start, at).toBigDecimalOrNull() ?: throw error("a bad number")
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!text.startsWith(word, at)) {
            throw error("'${text[at]}'")
        }
        at += word.length
        return value
    }

    private fun next(c: Char): Boolean {
        if (at < text.length && text[at] == c) {
            at++
            return true
        }
        return false
    }

    private fun expect(c: Char) {
        if (!next(c)) {
            throw error("no '$c'")
        }
    }

    private fun skipSpace() {
        while (at < text.length && text[at] in " \t\r\n") {
            at++
        }
    }

    private fun error(what: String) = IllegalArgumentException("Invalid JSON: $what at character $at")
}

package dev.screencopy.sender

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Android JSONTokener accepts non-JSON syntax; validate the wire grammar first. */
internal class Json private constructor(private val text: String) {
    private var pos = 0

    companion object {
        fun validate(text: String) {
            val parser = Json(text)
            parser.value(0)
            parser.space()
            if (parser.pos != text.length) throw IOException("PROTOCOL: trailing JSON content")
        }
        fun string(json: JSONObject, key: String): String =
            json.get(key) as? String ?: throw JSONException("String field required")

        fun integer(json: JSONObject, key: String): Int {
            val value = json.get(key)
            if (value !is Int && value !is Long) throw JSONException("Integer field required")
            val number = (value as Number).toLong()
            if (number !in Int.MIN_VALUE..Int.MAX_VALUE) throw JSONException("Integer field required")
            return number.toInt()
        }
        fun bool(json: JSONObject, key: String): Boolean =
            json.get(key) as? Boolean ?: throw JSONException("Boolean field required")
    }
    private fun space() {
        while (pos < text.length && text[pos] in " \r\n\t") pos++
    }
    private fun next(): Char {
        if (pos >= text.length) throw bad()
        return text[pos++]
    }
    private fun take(c: Char): Boolean {
        space()
        if (pos < text.length && text[pos] == c) { pos++; return true }
        return false
    }
    private fun expect(c: Char) { if (!take(c)) throw bad() }
    private fun bad() = IOException("PROTOCOL: invalid/duplicate/deep JSON")
    private fun string(): String {
        space()
        if (next() != '"') throw bad()
        val out = StringBuilder()
        while (true) {
            val c = next()
            if (c == '"') {
                var index = 0
                while (index < out.length) {
                    if (Character.isHighSurrogate(out[index])) {
                        index++
                        if (index >= out.length || !Character.isLowSurrogate(out[index])) throw bad()
                    } else if (Character.isLowSurrogate(out[index])) throw bad()
                    index++
                }
                return out.toString()
            }
            if (c.code < 32) throw bad()
            if (c == '\\') {
                val escaped = next()
                if (escaped == 'u') {
                    var value = 0
                    repeat(4) {
                        val digit = Character.digit(next(), 16)
                        if (digit < 0) throw bad()
                        value = value * 16 + digit
                    }
                    out.append(value.toChar())
                } else {
                    val index = "\"\\/bfnrt".indexOf(escaped)
                    if (index < 0) throw bad()
                    out.append("\"\\/\b\u000c\n\r\t"[index])
                }
            } else out.append(c)
        }
    }
    private fun value(depth: Int) {
        if (depth > 16) throw bad()
        space()
        if (pos >= text.length) throw bad()
        when (text[pos]) {
            '{' -> {
                pos++
                val keys = mutableSetOf<String>()
                if (take('}')) return
                while (true) {
                    if (!keys.add(string())) throw bad()
                    expect(':'); value(depth + 1)
                    if (take('}')) return
                    expect(',')
                }
            }
            '[' -> {
                pos++
                if (take(']')) return
                while (true) {
                    value(depth + 1)
                    if (take(']')) return
                    expect(',')
                }
            }
            '"' -> { string(); return }
        }
        for (literal in listOf("true", "false", "null")) {
            if (text.startsWith(literal, pos)) { pos += literal.length; return }
        }
        val start = pos
        while (pos < text.length && text[pos] in "-+0123456789.eE") pos++
        val number = text.substring(start, pos)
        if (!number.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) throw bad()
        try {
            if (!number.toDouble().isFinite()) throw bad()
            if (number.none { it in ".eE" } && number.toLong() !in Int.MIN_VALUE..Int.MAX_VALUE) throw bad()
        } catch (e: NumberFormatException) { throw bad() }
    }
}

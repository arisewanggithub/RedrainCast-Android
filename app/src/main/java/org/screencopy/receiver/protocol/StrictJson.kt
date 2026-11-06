package org.screencopy.receiver.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class ProtocolException(val code: String, message: String) : IllegalArgumentException(message)

fun protocolRequire(condition: Boolean, message: String, code: String = "PROTOCOL") {
    if (!condition) throw ProtocolException(code, message)
}

object StrictJson {
    fun decode(bytes: ByteArray): JSONObject {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw ProtocolException("PROTOCOL", "Malformed UTF-8")
        }
        return Parser(text).parse()
    }

    private class Parser(private val text: String) {
        private var position = 0
        fun parse(): JSONObject {
            val result = value(0)
            whitespace()
            protocolRequire(position == text.length && result is JSONObject, "Expected exactly one JSON object")
            return result as JSONObject
        }

        private fun value(depth: Int): Any {
            protocolRequire(depth <= 16, "JSON nesting exceeds 16 levels")
            whitespace()
            protocolRequire(position < text.length, "Truncated JSON")
            return when (text[position]) {
                '{' -> {
                    position++
                    val obj = JSONObject()
                    val keys = HashSet<String>()
                    whitespace()
                    if (!consume('}')) {
                        do {
                            whitespace()
                            protocolRequire(position < text.length && text[position] == '"', "Expected object key")
                            val key = string()
                            protocolRequire(keys.add(key), "Duplicate JSON key")
                            whitespace()
                            protocolRequire(consume(':'), "Expected colon")
                            obj.put(key, value(depth + 1))
                            whitespace()
                        } while (consume(','))
                        protocolRequire(consume('}'), "Unterminated JSON object")
                    }
                    obj
                }
                '[' -> {
                    position++
                    val array = JSONArray()
                    whitespace()
                    if (!consume(']')) {
                        do {
                            array.put(value(depth + 1))
                            whitespace()
                        } while (consume(','))
                        protocolRequire(consume(']'), "Unterminated JSON array")
                    }
                    array
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                else -> number()
            }
        }

        private fun number(): Number {
            val start = position
            consume('-')
            protocolRequire(position < text.length, "Truncated number")
            if (!consume('0')) {
                protocolRequire(text[position] in '1'..'9', "Invalid JSON value")
                while (position < text.length && text[position] in '0'..'9') position++
            }
            var integer = true
            if (consume('.')) {
                integer = false
                val digit = position
                while (position < text.length && text[position] in '0'..'9') position++
                protocolRequire(position > digit, "Invalid decimal number")
            }
            if (consume('e') || consume('E')) {
                integer = false
                if (!consume('+')) consume('-')
                val digit = position
                while (position < text.length && text[position] in '0'..'9') position++
                protocolRequire(position > digit, "Invalid exponent")
            }
            val raw = text.substring(start, position)
            if (integer) return raw.toIntOrNull() ?: throw ProtocolException("PROTOCOL", "JSON integer outside signed 32-bit range")
            val result = raw.toDoubleOrNull()
            protocolRequire(result != null && result.isFinite(), "Invalid JSON number")
            return checkNotNull(result)
        }

        private fun string(): String {
            position++
            val result = StringBuilder()
            while (position < text.length) {
                val char = text[position++]
                if (char == '"') {
                    var i = 0
                    while (i < result.length) {
                        val c = result[i++]
                        if (c.isHighSurrogate()) {
                            protocolRequire(i < result.length && result[i++].isLowSurrogate(), "Unpaired Unicode surrogate")
                        } else protocolRequire(!c.isLowSurrogate(), "Unpaired Unicode surrogate")
                    }
                    return result.toString()
                }
                protocolRequire(char.code >= 32, "Control character in JSON string")
                if (char != '\\') result.append(char) else {
                    protocolRequire(position < text.length, "Truncated JSON escape")
                    result.append(when (val escape = text[position++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            protocolRequire(position + 4 <= text.length, "Truncated Unicode escape")
                            val hex = text.substring(position, position + 4)
                            protocolRequire(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }, "Invalid Unicode escape")
                            val code = hex.toIntOrNull(16)
                            protocolRequire(code != null, "Invalid Unicode escape")
                            position += 4
                            checkNotNull(code).toChar()
                        }
                        else -> throw ProtocolException("PROTOCOL", "Invalid JSON escape")
                    })
                }
            }
            throw ProtocolException("PROTOCOL", "Unterminated JSON string")
        }

        private fun literal(expected: String, result: Any): Any {
            protocolRequire(text.startsWith(expected, position), "Invalid JSON literal")
            position += expected.length
            return result
        }
        private fun consume(char: Char): Boolean {
            if (position < text.length && text[position] == char) { position++; return true }
            return false
        }
        private fun whitespace() {
            while (position < text.length && text[position] in " \r\n\t") position++
        }
    }
}

fun JSONObject.string(key: String, max: Int = 65536): String {
    val value = opt(key)
    protocolRequire(value is String && value.length <= max, "Invalid or missing $key")
    return value as String
}

fun JSONObject.integer(key: String): Int {
    val value = opt(key)
    protocolRequire(value is Int, "Invalid or missing integer $key")
    return value as Int
}

fun JSONObject.boolean(key: String): Boolean {
    val value = opt(key)
    protocolRequire(value is Boolean, "Invalid or missing boolean $key")
    return value as Boolean
}

fun JSONObject.obj(key: String): JSONObject = optJSONObject(key)
    ?: throw ProtocolException("PROTOCOL", "Invalid or missing object $key")

fun JSONObject.array(key: String): JSONArray = optJSONArray(key)
    ?: throw ProtocolException("PROTOCOL", "Invalid or missing array $key")

package dev.screencopy.sender

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals

internal fun assertJsonEquals(expected: JSONObject, actual: JSONObject) {
    val expectedKeys = expected.keys().asSequence().toSet()
    assertEquals(expectedKeys, actual.keys().asSequence().toSet())
    for (key in expectedKeys) assertJsonValue(expected.get(key), actual.get(key))
}

private fun assertJsonValue(expected: Any, actual: Any) {
    when {
        expected is JSONObject && actual is JSONObject -> assertJsonEquals(expected, actual)
        expected is JSONArray && actual is JSONArray -> {
            assertEquals(expected.length(), actual.length())
            for (index in 0 until expected.length()) assertJsonValue(expected.get(index), actual.get(index))
        }
        else -> assertEquals(expected, actual)
    }
}

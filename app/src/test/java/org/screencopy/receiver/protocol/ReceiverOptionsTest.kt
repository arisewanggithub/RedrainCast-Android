package org.screencopy.receiver.protocol

import dev.screencopy.sender.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceiverOptionsTest {
    private fun json(raw: String) = StrictJson.decode(raw.toByteArray())
    private val implemented = ReceiverOptionProtocol.IMPLEMENTED

    @Test fun windowsRawFixturesPreserveRevisionBooleanDuplicateAndFutureRules() {
        val fixture = javaClass.getResourceAsStream("/receiver-options.json")!!.use { StrictJson.decode(it.readBytes()) }
        for (group in listOf("capabilities", "selections")) {
            val cases = fixture.getJSONArray(group)
            repeat(cases.length()) { index ->
                val case = cases.getJSONObject(index)
                val root = json(case.getString("json"))
                if (group == "capabilities")
                    assertEquals(case.getBoolean("enabled"), ReceiverOptionProtocol.capabilities(root).playbackStats)
                else {
                    assertEquals(case.getBoolean("enabled"), ReceiverOptionProtocol.selection(root, implemented).playbackStats)
                    assertFalse(ReceiverOptionProtocol.selection(root, ReceiverOptionCapabilities()).playbackStats)
                }
            }
        }
        for (group in listOf("invalidCapabilities", "invalidSelections")) {
            val cases = fixture.getJSONArray(group)
            repeat(cases.length()) { index ->
                assertThrows(cases.getString(index), ProtocolException::class.java) {
                    val root = json(cases.getString(index))
                    if (group == "invalidCapabilities") ReceiverOptionProtocol.capabilities(root)
                    else ReceiverOptionProtocol.selection(root, ReceiverOptionCapabilities())
                }
            }
        }
    }

    @Test fun exactUtf8EnvelopeBoundsIncludeWhitespaceAndUnknownMembers() {
        val prefix = """{"version":1,"supported":[],"future":""""
        val atLimit = prefix + "a".repeat(2048 - prefix.toByteArray().size - 2) + "\"}"
        assertEquals(2048, atLimit.toByteArray().size)
        assertFalse(ReceiverOptionProtocol.capabilities(json("""{"receiverOptions":$atLimit}""")).playbackStats)
        for (envelope in listOf(atLimit.dropLast(1) + " }", atLimit.replace("aaaa", "éééé"))) {
            assertThrows(ProtocolException::class.java) {
                ReceiverOptionProtocol.capabilities(json("""{"receiverOptions":$envelope}"""))
            }
        }
        for (count in listOf(16, 17)) {
            val names = (0 until count).joinToString(",") { "\"f$it\"" }
            val members = (0 until count - 2).joinToString("") { ",\"f$it\":null" }
            val raw = """{"receiverOptions":{"version":1,"supported":[$names]$members}}"""
            if (count == 16) assertFalse(ReceiverOptionProtocol.capabilities(json(raw)).playbackStats)
            else assertThrows(ProtocolException::class.java) { ReceiverOptionProtocol.capabilities(json(raw)) }
        }
        for (name in listOf("a".repeat(65), "\u0001")) {
            val root = JSONObject().put("receiverOptions", JSONObject().put("version", 1)
                .put("supported", org.json.JSONArray().put(name)))
            assertThrows(ProtocolException::class.java) { ReceiverOptionProtocol.capabilities(root) }
        }
    }

    @Test fun savedBooleanIsStrictDefaultOffAndNeverImplicitlySent() {
        val original = Settings()
        assertFalse(original.showPlaybackStatistics)
        original.showPlaybackStatistics = true
        val restored = Settings.from(original.saved())
        assertTrue(restored.showPlaybackStatistics)
        assertFalse(restored.request().has("receiverOptions"))
        assertFalse(restored.request().has("ShowPlaybackStatistics"))
        for (value in listOf(1, "true", JSONObject.NULL)) {
            assertThrows(IllegalArgumentException::class.java) {
                Settings.from(original.saved().put("ShowPlaybackStatistics", value))
            }
        }
        assertTrue(ReceiverOptionProtocol.apply(restored.request(), implemented.select(true))
            .getJSONObject("receiverOptions").getBoolean("playbackStats"))
        assertFalse(ReceiverOptionProtocol.apply(restored.request(), implemented.select(false)).has("receiverOptions"))
        assertFalse(ReceiverOptionProtocol.apply(restored.request(), ReceiverOptionCapabilities().select(true)).has("receiverOptions"))
    }
}

package org.screencopy.receiver.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticProxyNegotiationTest {
    private fun offer() = JSONObject().put("session", "77").put("pairId", "pair")
        .put("armGeneration", "9223372036854775807").put("receiverFlags", JSONArray().put("session"))
        .put("durationMs", 250).put("samplePeriodMs", 100)

    private fun hello(offer: JSONObject) = JSONObject().put("diagProxy",
        JSONObject().put("version", 2).put("offer", offer))

    private fun bootstrap(): JSONObject {
        val entries = JSONArray()
        DiagnosticProxyNegotiation.stages.forEachIndexed { index, stage ->
            entries.put(JSONObject().put("stage", stage).put("monoNs", "${index + 10}"))
        }
        return JSONObject().put("session", "77").put("pairId", "pair").put("captureId", "capture")
            .put("clockId", "clock").put("complete", true).put("loss", false).put("stages", entries)
    }

    @Test fun leaseStartsAtHelloReceiptAndRequestMatchesExactIdentity() {
        val value = checkNotNull(DiagnosticProxyNegotiation.offer(hello(offer()), 1_000_000_000))
        assertEquals(1_250_000_000L, value.expiresNs)
        assertEquals(Long.MAX_VALUE, value.generation)
        val request = JSONObject().put("diagProxy", JSONObject().put("version", 2).put("pairId", "pair")
            .put("armGeneration", "${Long.MAX_VALUE}"))
        DiagnosticProxyNegotiation.requestMatches(request, 77, value)
        request.getJSONObject("diagProxy").put("armGeneration", "9")
        assertThrows(ProtocolException::class.java) {
            DiagnosticProxyNegotiation.requestMatches(request, 77, value)
        }
        assertThrows(ProtocolException::class.java) {
            DiagnosticProxyNegotiation.requestMatches(request, 77, null)
        }
    }

    @Test fun offerRejectsBadTypesDuplicatesUnsupportedFlagsAndOverflow() {
        val invalid = listOf(
            offer().put("durationMs", 0), offer().put("samplePeriodMs", 5001),
            offer().put("session", 77), offer().put("armGeneration", "01"),
            offer().put("receiverFlags", JSONArray().put("session").put("session")),
            offer().put("receiverFlags", JSONArray().put("video.timing")),
            offer().put("receiverFlags", JSONArray().put("not-supported")),
            offer().put("extra", true)
        )
        invalid.forEach { value ->
            assertThrows(value.toString(), ProtocolException::class.java) {
                DiagnosticProxyNegotiation.offer(hello(value), 1_000_000_000)
            }
        }
        assertThrows(ProtocolException::class.java) {
            DiagnosticProxyNegotiation.offer(hello(offer()), Long.MAX_VALUE - 1)
        }
    }

    @Test fun bootstrapRequiresEightOrderedLocalStagesAndExactIdentityWithoutLoss() {
        DiagnosticProxyNegotiation.validateBootstrap(bootstrap(), 77, "pair", "capture", "clock")
        val bad = listOf(
            bootstrap().put("loss", true), bootstrap().put("complete", false),
            bootstrap().put("session", "78"), bootstrap().put("clockId", "other"),
            bootstrap().put("extra", 1),
            bootstrap().apply { getJSONArray("stages").getJSONObject(1).put("monoNs", "1") },
            bootstrap().apply { getJSONArray("stages").getJSONObject(7).put("stage", "capture_started") }
        )
        bad.forEach { value ->
            assertThrows(value.toString(), ProtocolException::class.java) {
                DiagnosticProxyNegotiation.validateBootstrap(value, 77, "pair", "capture", "clock")
            }
        }
    }

    @Test fun capabilityAcceptanceLaneAndReadyAreIdentityBound() {
        val selected = checkNotNull(DiagnosticProxyNegotiation.offer(hello(offer()), 1_000_000_000))
        val receiver = JSONObject().put("diagProxy", DiagnosticProxyNegotiation.receiverCapability())
        assertTrue(DiagnosticProxyNegotiation.supportsReceiver(receiver, setOf("session")))
        assertFalse(DiagnosticProxyNegotiation.supportsReceiver(receiver, setOf("unknown")))
        assertFalse(DiagnosticProxyNegotiation.supportsReceiver(JSONObject(), setOf("session")))
        val token = "ab".repeat(32)
        val acceptance = JSONObject().put("diagProxy", JSONObject().put("version", 2).put("pairId", "pair")
            .put("armGeneration", "${Long.MAX_VALUE}").put("laneToken", token)
            .put("captureId", "capture").put("clockId", "clock"))
        val accepted = DiagnosticProxyNegotiation.accepted(acceptance, selected)
        val lane = JSONObject().put("role", "diagnostic").put("version", 2).put("pairId", "pair")
            .put("armGeneration", "${Long.MAX_VALUE}").put("token", token).put("remainingMs", 100)
        assertEquals(100, DiagnosticProxyNegotiation.laneHello(lane, 77, selected, token))
        assertThrows(ProtocolException::class.java) {
            DiagnosticProxyNegotiation.laneHello(lane, 78, selected, token)
        }
        assertThrows(ProtocolException::class.java) {
            DiagnosticProxyNegotiation.laneHello(lane.put("token", "ba".repeat(32)), 77, selected, token)
        }
        val ready = JSONObject().put("version", 2).put("state", "ready").put("pairId", "pair")
            .put("armGeneration", "${Long.MAX_VALUE}").put("captureId", "capture").put("clockId", "clock")
            .put("bootstrap", bootstrap())
        assertEquals(bootstrap().toString(), DiagnosticProxyNegotiation.ready(ready, selected, accepted).toString())
        ready.put("captureId", "other")
        assertThrows(ProtocolException::class.java) {
            DiagnosticProxyNegotiation.ready(ready, selected, accepted)
        }
    }
}

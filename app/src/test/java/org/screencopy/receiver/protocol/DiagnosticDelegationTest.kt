package org.screencopy.receiver.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticDelegationTest {
    @Test fun missingLegacyAndFutureCapabilityDoNotAuthorizeDelegation() {
        assertFalse(DiagnosticDelegation.supported(JSONObject()))
        assertFalse(DiagnosticDelegation.supported(JSONObject().put("diagnostics",
            JSONObject().put("version", 2).put("sessionDelegation", true))))
        assertTrue(DiagnosticDelegation.supported(JSONObject().put("diagnostics", DiagnosticDelegation.capability())))
        assertFalse(DiagnosticDelegation.requested(JSONObject()))
    }
    @Test fun requestAndCapabilityTypesDuplicatesAndUnknownRequestFieldsAreStrict() {
        for (text in listOf(
            """{"diagnostic":null}""", """{"diagnostic":{"version":1,"enabled":"true"}}""",
            """{"diagnostic":{"version":1.0,"enabled":true}}""",
            """{"diagnostic":{"version":2,"enabled":true}}""",
            """{"diagnostic":{"version":1,"enabled":true,"other":0}}""",
            """{"diagnostic":{"version":1,"enabled":true,"enabled":false}}"""
        )) {
            try { DiagnosticDelegation.requested(StrictJson.decode(text.toByteArray())); fail(text) }
            catch (_: IllegalArgumentException) {} catch (_: ProtocolException) {}
        }
        for (value in listOf(JSONObject.NULL, "true", 1.0)) {
            try {
                DiagnosticDelegation.supported(JSONObject().put("diagnostics", JSONObject().put("version", 1)
                    .put("sessionDelegation", value)))
                fail("$value")
            } catch (_: ProtocolException) {}
        }
        assertTrue(DiagnosticDelegation.requested(JSONObject().put("diagnostic", DiagnosticDelegation.request(true))))
        assertFalse(DiagnosticDelegation.requested(JSONObject().put("diagnostic", DiagnosticDelegation.request(false))))
    }
}

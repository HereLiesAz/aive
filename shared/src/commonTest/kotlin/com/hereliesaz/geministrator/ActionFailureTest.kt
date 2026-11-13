package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionFailureTest {
    @Test
    fun aFailureKeepsWhatWasBeingDoneAndTheRawText() {
        val failure = actionFailureOf("Approval failed", IllegalStateException("HTTP 429: slow down"))
        assertEquals("Approval failed", failure.what)
        assertEquals("HTTP 429: slow down", failure.raw)
    }

    @Test
    fun aFailureWithNoMessageFallsBackToItsTypeName() {
        assertEquals("IllegalStateException", actionFailureOf("Stop failed", IllegalStateException()).raw)
        assertEquals("IllegalStateException", actionFailureOf("Stop failed", IllegalStateException("   ")).raw)
    }

    @Test
    fun aBadRequestFromTheProviderReadsAsOne() {
        val raw = "HTTP 400: {error: Missing required parameter: 'messages'}"
        assertEquals(ErrorKind.BadRequest, classifyError(raw))
        assertEquals(ErrorKind.BadRequest, classifyError("invalid_request_error: bad field"))
    }

    @Test
    fun otherCodesAreNotSwallowedByTheBadRequestRule() {
        assertEquals(ErrorKind.KeyRejected, classifyError("HTTP 401: Unauthorized"))
        assertEquals(ErrorKind.RateLimited, classifyError("HTTP 429: too many requests"))
        assertEquals(ErrorKind.Other, classifyError("something odd"))
    }

    @Test
    fun aSentenceTheAppWroteIsPlainButProviderTextIsNot() {
        assertTrue(isPlainReason("Tests did not pass"))
        assertTrue(isPlainReason("Held for a reason"))
        assertFalse(isPlainReason(null))
        assertFalse(isPlainReason("  "))
        assertFalse(isPlainReason("HTTP 400: {error: Missing required parameter}"))
        assertFalse(isPlainReason("""{"error":{"message":"nope"}}"""))
        assertFalse(isPlainReason("java.lang.IllegalStateException: boom"))
        assertFalse(isPlainReason("HTTP 401 Unauthorized"))
        assertFalse(isPlainReason("x".repeat(400)))
    }

    @Test
    fun aFailureTravelsAsOneStringWithTheRawTextAfterTheFirstLine() {
        val packed = packFailureText("The provider did not accept this request.", "HTTP 400: {error}")
        assertEquals("The provider did not accept this request.\nHTTP 400: {error}", packed)
        assertEquals("The provider did not accept this request." to "HTTP 400: {error}", unpackFailureText(packed))
        assertEquals("Just a sentence" to null, unpackFailureText("Just a sentence"))
        assertEquals("Only summary" to null, packFailureText("Only summary", "  ").let(::unpackFailureText))
        assertEquals(300, unpackFailureText(packFailureText("s", "r".repeat(900))).second?.length)
    }
}

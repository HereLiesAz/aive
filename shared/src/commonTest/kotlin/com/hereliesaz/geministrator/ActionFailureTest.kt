package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals

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
}

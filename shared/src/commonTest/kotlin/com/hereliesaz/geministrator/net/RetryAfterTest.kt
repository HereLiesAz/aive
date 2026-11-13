package com.hereliesaz.geministrator.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryAfterTest {
    @Test
    fun theHeaderIsReadAsSecondsAndCapped() {
        assertEquals(7_000L, parseRetryAfterMillis(" 7 "))
        assertEquals(MAX_RETRY_AFTER_MILLIS, parseRetryAfterMillis("86400"))
        assertNull(parseRetryAfterMillis("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(parseRetryAfterMillis(null))
        assertNull(parseRetryAfterMillis("-3"))
    }

    @Test
    fun backoffDoublesIsCappedAndHonoursTheServer() {
        assertEquals(1_000L, backoffMillis(1))
        assertEquals(2_000L, backoffMillis(2))
        assertEquals(4_000L, backoffMillis(3))
        assertEquals(60_000L, backoffMillis(40))
        assertEquals(30_000L, backoffMillis(1, retryAfterMillis = 30_000L), "never sooner than Retry-After")
        assertEquals(60_000L, backoffMillis(10, retryAfterMillis = 5_000L), "the longer of the two")
    }

    @Test
    fun theHintIsFoundOnTheCauseChain() {
        val failure = RuntimeException("outer", HttpStatusFailure(429, "slow down", retryAfterMillis = 12_000L))
        assertEquals(12_000L, failure.retryAfterHintMillis())
        assertNull(RuntimeException("plain").retryAfterHintMillis())
    }
}

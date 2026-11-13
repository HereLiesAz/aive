package com.hereliesaz.geministrator

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CancellableCatchingTest {
    @Test
    fun aValueIsReturnedAsASuccess() {
        assertEquals(3, runCatchingCancellable { 1 + 2 }.getOrNull())
    }

    @Test
    fun anOrdinaryFailureIsCaptured() {
        val result = runCatchingCancellable<Int> { error("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun cancellationIsRethrownNotCaptured() {
        assertFailsWith<CancellationException> { runCatchingCancellable<Int> { throw CancellationException("stop") } }
    }
}

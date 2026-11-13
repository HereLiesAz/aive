package com.hereliesaz.geministrator.providers.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class A2aPollBackoffTest {
    @Test
    fun aTaskThatKeepsWorkingIsPolledLessAndLessOftenUpToACap() {
        assertEquals(2_000L, a2aPollDelayMillis(2_000L, 0))
        assertEquals(2_000L, a2aPollDelayMillis(2_000L, 5), "the first polls stay quick")
        assertEquals(3_000L, a2aPollDelayMillis(2_000L, 6))
        assertTrue(a2aPollDelayMillis(2_000L, 7) > a2aPollDelayMillis(2_000L, 6))
        assertEquals(30_000L, a2aPollDelayMillis(2_000L, 500), "never slower than the cap")
    }

    @Test
    fun theObserveCapIsTwoHoursByDefault() {
        assertEquals(2L * 60 * 60 * 1000, A2A_DEFAULT_MAX_OBSERVE_MILLIS)
    }
}

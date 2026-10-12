package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The one constructor-time guard [ActState] carries: work in progress is a fraction, or unknown,
 * and nothing else.
 */
class ActStateTest {

    @Test
    fun `extent is a fraction, and nothing outside it can be constructed`() {
        assertFailsWith<IllegalArgumentException> { ActState.Yielding(-0.01f) }
        assertFailsWith<IllegalArgumentException> { ActState.Yielding(1.01f) }
    }

    @Test
    fun `the boundaries themselves are fine, as is not knowing`() {
        ActState.Yielding(0f)
        ActState.Yielding(1f)
        ActState.Yielding(null)
        ActState.Yielding()
    }
}

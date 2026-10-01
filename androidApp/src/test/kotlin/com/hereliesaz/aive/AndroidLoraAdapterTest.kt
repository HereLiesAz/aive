package com.hereliesaz.aive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidLoraAdapterTest {
    @Test
    fun halfPrecisionConversionMatchesExpectedValues() {
        val cases = mapOf(
            0x0000.toShort() to 0.0f,
            0x8000.toShort() to -0.0f,
            0x3C00.toShort() to 1.0f,
            0xC000.toShort() to -2.0f,
            0x7C00.toShort() to Float.POSITIVE_INFINITY,
        )

        cases.forEach { (half, expected) ->
            assertEquals(expected, AndroidLoraAdapter.halfToFloat(half))
        }
    }

    @Test
    fun halfPrecisionNaNStaysNaN() {
        assertTrue(AndroidLoraAdapter.halfToFloat(0x7E00.toShort()).isNaN())
    }
}

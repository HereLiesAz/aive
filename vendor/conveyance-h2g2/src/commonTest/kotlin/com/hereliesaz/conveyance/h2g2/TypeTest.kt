package com.hereliesaz.conveyance.h2g2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TypeTest {

    @Test
    fun `step resolves every real step name to its own step`() {
        val type = h2g2Type()
        assertEquals(type.hero, type.step("hero"))
        assertEquals(type.section, type.step("section"))
        assertEquals(type.lead, type.step("lead"))
        assertEquals(type.body, type.step("body"))
        assertEquals(type.capsule, type.step("capsule"))
        assertEquals(type.eyebrow, type.step("eyebrow"))
        assertEquals(type.endCap, type.step("endCap"))
        assertEquals(type.micro, type.step("micro"))
    }

    @Test
    fun `step falls back to body for an unrecognized scale name`() {
        val type = h2g2Type()
        assertEquals(type.body, type.step(""))
        assertEquals(type.body, type.step("titleMedium"))
        assertEquals(type.body, type.step("Hero"))
    }

    @Test
    fun `the eight steps are genuinely distinct text styles`() {
        val type = h2g2Type()
        val steps = listOf(
            type.hero, type.section, type.lead, type.body,
            type.capsule, type.eyebrow, type.endCap, type.micro,
        )
        steps.forEachIndexed { i, a ->
            steps.forEachIndexed { j, b ->
                if (i != j) assertNotEquals(a, b, "Steps $i and $j should not collapse to the same style.")
            }
        }
    }
}

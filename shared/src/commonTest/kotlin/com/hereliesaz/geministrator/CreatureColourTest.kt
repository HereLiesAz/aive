package com.hereliesaz.geministrator

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CreatureColourTest {
    private fun distance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    @Test
    fun anEggsShellIsTheSameEveryTimeAndDiffersBetweenTasks() {
        assertEquals(eggBaseTint("build:task-1"), eggBaseTint("build:task-1"))
        val tints = (1..12).map { eggBaseTint("role:task-$it") }.toSet()
        assertTrue(tints.size > 6, "twelve tasks should not share a handful of shells: ${tints.size}")
    }

    @Test
    fun aGestatorThatHasNotBegunLeavesTheShellAlone() {
        val base = eggBaseTint("a")
        assertTrue(distance(base, blendEggTint(base, listOf(Color.Red to 0f))) < 0.01f)
        assertTrue(distance(base, blendEggTint(base, emptyList())) < 0.01f)
    }

    @Test
    fun theEggTakesOnTheColourOfWhoeverWorksOnIt() {
        val base = eggBaseTint("a")
        val blue = Color(0xFF2060D0)
        val early = blendEggTint(base, listOf(blue to 0.25f))
        val late = blendEggTint(base, listOf(blue to 1f))
        assertTrue(distance(late, blue) < distance(early, blue), "more work pulls it closer to the worker")
        assertTrue(distance(early, blue) < distance(base, blue), "any work pulls it toward the worker")
    }

    @Test
    fun twoGestatorsBothShowInTheBlend() {
        val base = eggBaseTint("a")
        val red = Color(0xFFD03020)
        val blue = Color(0xFF2060D0)
        val both = blendEggTint(base, listOf(red to 1f, blue to 1f))
        val onlyRed = blendEggTint(base, listOf(red to 1f))
        val onlyBlue = blendEggTint(base, listOf(blue to 1f))
        assertNotEquals(onlyRed, both)
        assertNotEquals(onlyBlue, both)
        assertTrue(distance(both, red) < distance(onlyBlue, red), "red is in it")
        assertTrue(distance(both, blue) < distance(onlyRed, blue), "blue is in it")
    }
}

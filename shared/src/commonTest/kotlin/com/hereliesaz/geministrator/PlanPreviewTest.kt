package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanPreviewTest {
    @Test
    fun aShortPlanIsShownWhole() {
        val preview = planPreview("1. Read the repo\n2. Fix the bug")!!
        assertEquals("1. Read the repo\n2. Fix the bug", preview.shown)
        assertFalse(preview.truncated)
    }

    @Test
    fun aLongPlanShowsItsFirstLinesAndSaysThereIsMore() {
        val plan = (1..20).joinToString("\n") { "step $it" }
        val preview = planPreview(plan, maxLines = 5)!!
        assertEquals((1..5).joinToString("\n") { "step $it" }, preview.shown)
        assertTrue(preview.truncated)
    }

    @Test
    fun aLongSingleLineIsCutAtTheCharacterLimit() {
        val preview = planPreview("x".repeat(2000), maxChars = 100)!!
        assertEquals(100, preview.shown.length)
        assertTrue(preview.truncated)
    }

    @Test
    fun noPlanTextMeansNoPreview() {
        assertNull(planPreview(null))
        assertNull(planPreview("   \n "))
    }
}

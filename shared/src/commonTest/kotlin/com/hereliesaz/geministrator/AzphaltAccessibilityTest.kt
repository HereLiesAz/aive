package com.hereliesaz.geministrator

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AzphaltAccessibilityTest {
    @Test
    fun bestTextPicksTheHigherContrastOfInkAndWhite() {
        // Mid-tones where the old luminance threshold chose White at under 3.4:1.
        val midTones = listOf(
            Color(0xFF2FA9C4), Color(0xFF5AAE34), Color(0xFF1F9E86),
            Color(0xFFD9762A), Color(0xFF8A9296), Color(0xFFD4728F),
        )
        midTones.forEach { background ->
            val chosen = bestTextOn(background)
            val other = if (chosen == Azphalt.Ink) Azphalt.White else Azphalt.Ink
            assertTrue(
                contrastRatio(chosen, background) >= contrastRatio(other, background),
                "wrong text colour for $background",
            )
            assertTrue(contrastRatio(chosen, background) >= 4.5f, "below 4.5:1 on $background")
        }
        assertEquals(Azphalt.Ink, bestTextOn(Color(0xFF2FA9C4)))
        assertEquals(Azphalt.White, bestTextOn(Color(0xFF4B3489)))
        assertEquals(Azphalt.White, bestTextOn(Azphalt.Ink))
    }

    @Test
    fun contrastRatioMatchesWcagExtremes() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.White, Color.White), 0.001f)
    }

    @Test
    fun yellowAccentIsNeverUsedOnMustard() {
        val mustard = Azphalt.grounds.first { it.name == "Mustard" }
        assertEquals(mustard.onPage, mustard.accent)
        assertTrue(contrastRatio(mustard.accent, mustard.page) >= 4.5f)
        val navy = Azphalt.grounds.first { it.name == "Navy" }
        assertEquals(Azphalt.Yellow, navy.accent)
    }

    @Test
    fun everyGroundHasReadablePageText() {
        Azphalt.grounds.forEach { ground ->
            assertTrue(contrastRatio(ground.onPage, ground.page) >= 4.5f, "${ground.name} page text")
        }
    }

    @Test
    fun smallTextIsAtLeastElevenSpWithOpenLeading() {
        listOf(AzphaltType.eyebrow, AzphaltType.endCap, AzphaltType.capsule).forEach { style ->
            assertTrue(style.fontSize.value >= 11f)
            assertTrue(style.lineHeight.value >= style.fontSize.value * 1.2f)
        }
    }

    @Test
    fun reducedMotionFollowsAnimatorScaleOrSystemPreference() {
        assertTrue(shouldReduceMotion(animatorDurationScale = 0f, systemPrefersReduced = false))
        assertTrue(shouldReduceMotion(animatorDurationScale = null, systemPrefersReduced = true))
        assertTrue(shouldReduceMotion(animatorDurationScale = 1f, systemPrefersReduced = true))
        assertFalse(shouldReduceMotion(animatorDurationScale = 1f, systemPrefersReduced = false))
        assertFalse(shouldReduceMotion(animatorDurationScale = null, systemPrefersReduced = false))
        assertFalse(shouldReduceMotion(animatorDurationScale = 0.5f, systemPrefersReduced = false))
    }
}

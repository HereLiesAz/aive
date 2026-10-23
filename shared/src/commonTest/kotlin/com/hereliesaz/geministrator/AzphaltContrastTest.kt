package com.hereliesaz.geministrator

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Content colour is always derived from the surface it sits on ([oppositeOf]). These tests walk
 * every colour token the app paints a surface with and hold the helper to the WCAG thresholds.
 */
class AzphaltContrastTest {
    /** Every colour the app uses as a surface under text, icons or glyphs, by token name. */
    private val surfaces: Map<String, Color> = buildMap {
        put("Ink", Azphalt.Ink)
        put("Yellow", Azphalt.Yellow)
        put("White", Azphalt.White)
        put("Black", Color.Black)
        Azphalt.hues.forEachIndexed { index, color -> put("hue[$index]", color) }
        Azphalt.caps.forEachIndexed { index, color -> put("cap[$index]", color) }
        Azphalt.grounds.forEach { ground ->
            put("${ground.name}.page", ground.page)
            put("${ground.name}.foldLight", ground.foldLight)
            put("${ground.name}.foldDark", ground.foldDark)
        }
    }

    @Test
    fun theTokenTableCoversEveryPaletteColour() {
        val expected = 4 + Azphalt.hues.size + Azphalt.caps.size + Azphalt.grounds.size * 3
        assertEquals(expected, surfaces.size)
    }

    @Test
    fun onOppositeIsPureBlackOrPureWhiteForEverySurface() {
        surfaces.forEach { (name, surface) ->
            val on = surface.onOpposite()
            assertTrue(on == Color.Black || on == Color.White, "$name gave $on")
        }
    }

    @Test
    fun onOppositeIsTheHigherContrastChoiceAndClearsTextContrastOnEverySurface() {
        surfaces.forEach { (name, surface) ->
            val on = surface.onOpposite()
            val other = if (on == Color.Black) Color.White else Color.Black
            val ratio = contrastRatio(on, surface)
            assertTrue(ratio >= contrastRatio(other, surface), "$name: the other colour reads better")
            assertTrue(ratio >= AZPHALT_MIN_TEXT_CONTRAST, "$name: ${ratio}:1 is under 4.5:1")
            assertTrue(ratio >= AZPHALT_MIN_LARGE_CONTRAST, "$name: ${ratio}:1 is under 3:1 for large text and icons")
        }
    }

    @Test
    fun onOppositeIsNeverTheBackgroundOrANearMatch() {
        surfaces.forEach { (name, surface) ->
            val on = surface.onOpposite()
            assertNotEquals(surface, on, "$name returned its own background")
            assertTrue(contrastRatio(on, surface) >= 4.5f, "$name returned a near match")
        }
    }

    @Test
    fun oppositeOfMatchesOnOpposite() {
        surfaces.values.forEach { surface -> assertEquals(surface.onOpposite(), oppositeOf(surface)) }
    }

    @Test
    fun theWorstCaseSurfaceStillClearsFourPointFive() {
        // The two colours' contrast crosses over at relative luminance about 0.179; sweep the grey
        // axis finely so no mid-tone slips under the threshold.
        for (level in 0..255) {
            val grey = Color(level / 255f, level / 255f, level / 255f)
            assertTrue(contrastRatio(grey.onOpposite(), grey) >= 4.5f, "grey $level")
        }
    }

    @Test
    fun aTranslucentBackgroundIsResolvedOverTheSurfaceBeneathIt() {
        Azphalt.grounds.forEach { ground ->
            listOf(Color.Black.copy(alpha = 0.55f), Azphalt.Ink.copy(alpha = 0.45f), Azphalt.Yellow.copy(alpha = 0.3f)).forEach { overlay ->
                val seen = overlay.compositeOver(ground.page)
                val on = overlay.onOpposite(under = ground.page)
                assertEquals(seen.onOpposite(), on, "${ground.name} under $overlay")
                assertTrue(contrastRatio(on, seen) >= 4.5f, "${ground.name} under $overlay")
            }
        }
    }

    @Test
    fun mutedContentNeverDropsUnderThreeToOneOnAnySurface() {
        surfaces.forEach { (name, surface) ->
            val muted = surface.mutedOnOpposite()
            assertTrue(contrastRatio(muted, surface) >= AZPHALT_MIN_LARGE_CONTRAST, "$name muted under 3:1")
            assertNotEquals(surface, muted, "$name muted equals its background")
        }
    }

    @Test
    fun mutedContentAtTextContrastNeverDropsUnderFourPointFive() {
        surfaces.forEach { (name, surface) ->
            val muted = surface.mutedOnOpposite(AZPHALT_MIN_TEXT_CONTRAST)
            assertTrue(contrastRatio(muted, surface) >= AZPHALT_MIN_TEXT_CONTRAST, "$name muted under 4.5:1")
        }
    }

    @Test
    fun noPaletteSurfaceYieldsAnOnColourBelowThreshold() {
        surfaces.forEach { (name, surface) ->
            val scheme = azphaltColorScheme(surface)
            val pairs = listOf(
                "onSurface" to (scheme.onSurface to scheme.surface),
                "onBackground" to (scheme.onBackground to scheme.background),
                "onSurfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                "onPrimary" to (scheme.onPrimary to scheme.primary),
                "onSecondary" to (scheme.onSecondary to scheme.secondary),
                "onTertiary" to (scheme.onTertiary to scheme.tertiary),
                "onError" to (scheme.onError to scheme.error),
                "onPrimaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "onTertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "onErrorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
                "inverseOnSurface" to (scheme.inverseOnSurface to scheme.inverseSurface),
                // Accents are drawn on the surface itself, so they must read on it too.
                "primary on surface" to (scheme.primary to scheme.surface),
                "error on surface" to (scheme.error to scheme.surface),
                "onSurface on surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
            )
            pairs.forEach { (role, pair) ->
                assertTrue(
                    contrastRatio(pair.first, pair.second) >= AZPHALT_MIN_TEXT_CONTRAST,
                    "$name scheme: $role is under 4.5:1",
                )
            }
            assertTrue(contrastRatio(scheme.outline, scheme.surface) >= AZPHALT_MIN_LARGE_CONTRAST, "$name outline under 3:1")
        }
    }

    @Test
    fun everyGroundPageTextAndAccentClearTheirThresholds() {
        Azphalt.grounds.forEach { ground ->
            assertEquals(ground.page.onOpposite(), ground.onPage)
            assertTrue(contrastRatio(ground.onPage, ground.page) >= 4.5f, "${ground.name} page text")
            assertTrue(contrastRatio(ground.accent, ground.page) >= 3f, "${ground.name} accent")
            // The ground's folds carry text too (cards laid on them).
            assertTrue(contrastRatio(ground.foldLight.onOpposite(), ground.foldLight) >= 4.5f, "${ground.name} light fold")
            assertTrue(contrastRatio(ground.foldDark.onOpposite(), ground.foldDark) >= 4.5f, "${ground.name} dark fold")
        }
    }

    @Test
    fun pillsRecordsAndNotesReadOnEveryHueAndCap() {
        // A selected pill or record is an Ink surface; its end cap is Yellow. Unselected ones use
        // a hue with a cap beside it. Each label is the opposite of its own fill.
        assertEquals(Color.White, Azphalt.Ink.onOpposite())
        assertEquals(Color.Black, Azphalt.Yellow.onOpposite())
        Azphalt.hues.indices.forEach { index ->
            assertTrue(contrastRatio(Azphalt.hues[index].onOpposite(), Azphalt.hues[index]) >= 4.5f, "hue $index")
            assertTrue(contrastRatio(Azphalt.caps[index].onOpposite(), Azphalt.caps[index]) >= 4.5f, "cap $index")
        }
    }

    @Test
    fun theWellOfARecordIsReadableOnEveryGround() {
        // The Memory screen bug: text in a record's Ink well took the page's colour (Ink on
        // Mustard and Pink), so the well rendered as an empty black block. Inside the well the
        // text is the opposite of the well, whatever the ground.
        Azphalt.grounds.forEach { ground ->
            val onWell = Azphalt.Ink.onOpposite()
            assertTrue(contrastRatio(onWell, Azphalt.Ink) >= 4.5f, "${ground.name} well")
            // A page-coloured label would be the old, invisible one on Mustard and Pink.
            assertTrue(
                contrastRatio(ground.onPage, Azphalt.Ink) < 4.5f || ground.onPage == onWell,
                "${ground.name}: page text in the well would be ${ground.onPage}",
            )
        }
        assertEquals(Color.White, Azphalt.Ink.onOpposite())
    }

    @Test
    fun theButtonLabelAlwaysReadsOnItsContainerAndDisabledOnesStayAtThreeToOne() {
        Azphalt.grounds.forEach { ground ->
            val container = primaryButtonContainerOn(ground.page)
            assertTrue(contrastRatio(container, ground.page) >= AZPHALT_MIN_LARGE_CONTRAST, "${ground.name} button vs ground")
            assertTrue(contrastRatio(container.onOpposite(), container) >= 4.5f, "${ground.name} button label")
            val disabled = container.copy(alpha = 0.45f).compositeOver(ground.page)
            assertTrue(contrastRatio(disabled.mutedOnOpposite(), disabled) >= 3f, "${ground.name} disabled label")
        }
        // On an Ink well, Ink would melt in: the button is White there.
        assertEquals(Azphalt.White, primaryButtonContainerOn(Azphalt.Ink))
    }

    @Test
    fun theLaunchScrimTextClearsFourPointFiveOverAnythingBeneathIt() {
        val scrim = Color.Black.copy(alpha = 0.55f)
        surfaces.forEach { (name, beneath) ->
            val seen = scrim.compositeOver(beneath)
            assertEquals(Color.White, scrim.onOpposite(under = beneath), "scrim over $name")
            assertTrue(contrastRatio(Color.White, seen) >= 4.5f, "scrim over $name")
        }
    }
}

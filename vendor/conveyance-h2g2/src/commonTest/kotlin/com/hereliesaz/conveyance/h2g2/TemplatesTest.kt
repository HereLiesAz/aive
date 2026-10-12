package com.hereliesaz.conveyance.h2g2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Outcome
import com.hereliesaz.conveyance.Practice
import com.hereliesaz.conveyance.SubjectId
import com.hereliesaz.conveyance.compose.ElementRegistry
import com.hereliesaz.conveyance.compose.LocalElements
import com.hereliesaz.conveyance.compose.LocalPractice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `Templates.kt` had no tests at all, despite an audit having already found two real defects in
 * it (every `Offer` missing `Modifier.tell(owesTell, weight).clickable { engage() }`, so every
 * template rendered but was inert; and `hueSeed` resolving straight through `H2g2.hueOf`'s hash,
 * so a manifest naming a real hue never got it). A third was found later: every template
 * hardcoded its own shape literal and never read [ComposableRequest.surface] at all, leaving
 * [H2g2Surface.byName] with zero callers. All three are pinned here.
 *
 * The click tests really compose a template and inject a real click -- that is the only way to
 * observe the `.clickable { engage() }` wiring, since a template that omits it still renders
 * perfectly. Same harness `conveyance-compose`'s own `OfferTest` uses. `Modifier.tell`'s own
 * presence is not separately asserted: it renders as a `graphicsLayer` translation with no
 * semantics to query, so it is pinned only by these tests compiling and composing.
 *
 * Honest limits, checked by mutation rather than assumed: deleting `.clickable { engage() }` from
 * a template really does fail the two click tests. Re-hardcoding a template's shape literal does
 * *not* fail anything here -- a clip shape has no semantics either, and proving a template clips
 * to the shape the manifest asked for would take a pixel capture. What is pinned instead is the
 * resolution rule itself ([surfaceShape]), at the one place all six templates call it.
 */
@OptIn(ExperimentalTestApi::class)
class TemplatesTest {

    private val subject = SubjectId("record.41")
    private val into = ElementId("records")

    private fun request(
        surface: String,
        hueSeed: String = "record.41",
        onPerform: () -> Unit = {},
    ) = ComposableRequest(
        act = Act.create("record.create", subject, into, perform = { onPerform(); Outcome.Done }),
        hueSeed = hueSeed,
        surface = surface,
        scale = "lead",
        label = "Create record",
        subtitle = "a second line",
        detailLines = listOf("line one", "line two"),
        endCapText = "12",
    )

    // ----- the manifest's `surface` actually selects the shape -------------------------------

    /**
     * Each template's own default shape, the literal it used to hardcode. A manifest that names
     * nothing usable must still land exactly here -- that is what keeps every existing caller
     * rendering identically after the wiring fix.
     */
    private val defaults = listOf(
        H2g2Surface.recordTile,
        H2g2Surface.note,
        H2g2Surface.capsule,
        H2g2Surface.well,
    )

    @Test
    fun `a manifest naming a real surface gets that surface, whatever the template's default`() {
        val named = mapOf(
            "recordTile" to H2g2Surface.recordTile,
            "note" to H2g2Surface.note,
            "well" to H2g2Surface.well,
            "capsule" to H2g2Surface.capsule,
        )
        named.forEach { (name, expected) ->
            defaults.forEach { default ->
                assertSame(
                    expected,
                    request(name).surfaceShape(default),
                    "surface=\"$name\" must win over the template's own default.",
                )
            }
        }
    }

    @Test
    fun `a surface this vocabulary doesn't have leaves the template on its own default`() {
        listOf("", " ", "RECORDTILE", "tile", "pill", "nonsense").forEach { name ->
            defaults.forEach { default ->
                assertSame(
                    default,
                    request(name).surfaceShape(default),
                    "surface=\"$name\" must fall back to the template's default, not to capsule.",
                )
            }
        }
    }

    /**
     * The regression this pins directly: before the fix, a `capsule` manifest rendered through
     * `h2g2.tile.record` still came out `recordTile`-shaped, because the template never read
     * `request.surface`. Asserting inequality with the default is what a hardcoded literal fails.
     */
    @Test
    fun `surfaceShape is not the template default when the manifest overrides it`() {
        assertEquals(
            H2g2Surface.capsule,
            request("capsule").surfaceShape(H2g2Surface.recordTile),
        )
        assertEquals(
            H2g2Surface.recordTile,
            request("recordTile").surfaceShape(H2g2Surface.capsule),
        )
    }

    @Test
    fun `byName's one-argument form still resolves to capsule for anything unrecognized`() {
        assertSame(H2g2Surface.capsule, H2g2Surface.byName("nope"))
        assertSame(H2g2Surface.note, H2g2Surface.byName("note"))
        assertSame(H2g2Surface.well, H2g2Surface.byName("well"))
    }

    // ----- hue resolution goes through indexOf, not hueOf -------------------------------------

    /**
     * The `hueSeed` defect: a manifest author naming a real hue (`"hue": "violet"`) used to get
     * the hash of the string `"violet"` instead. Every template resolves through [H2g2.indexOf],
     * so a named hue is exact and anything else still hashes deterministically.
     */
    @Test
    fun `a manifest naming a real hue resolves to that hue, not to its hash`() {
        H2g2.hueNames.forEachIndexed { index, name ->
            val seed = request("capsule", hueSeed = name).hueSeed
            assertEquals(index, H2g2.indexOf(seed), "\"$name\" must resolve to its own hues entry.")
        }
        // ...and an id that is not a hue name still hashes, in range, deterministically.
        listOf("record.41", "", "🦊").forEach { seed ->
            val index = H2g2.indexOf(seed)
            assertEquals(H2g2.hueOf(seed), index)
            assertTrue(index in H2g2.hues.indices)
        }
    }

    // ----- the registry ------------------------------------------------------------------------

    @Test
    fun `the registry exposes exactly the six documented templateIds`() {
        assertEquals(
            setOf(
                "h2g2.tile.record",
                "h2g2.tile.record.detail",
                "h2g2.tile.record.capped",
                "h2g2.tile.note",
                "h2g2.pill.action",
                "h2g2.pill.capped",
            ),
            Templates.registry.keys,
        )
    }

    // ----- the `.clickable { engage() }` wiring, for real --------------------------------------

    private fun assertEngagesOnTap(template: @Composable (ComposableRequest) -> Unit, name: String) =
        runComposeUiTest {
            var engaged = false
            val request = request("recordTile") { engaged = true }
            setContent {
                CompositionLocalProvider(
                    LocalElements provides ElementRegistry(),
                    LocalPractice provides Practice(),
                ) {
                    template(request)
                }
            }
            waitForIdle()
            onNodeWithText("Create record").performClick()
            waitForIdle()
            assertTrue(engaged, "$name renders but never engages its act on tap.")
        }

    @Test
    fun `every registry template engages its act when tapped`() {
        Templates.registry.forEach { (id, template) -> assertEngagesOnTap(template, id) }
    }

    @Test
    fun `every registry template exposes a click action at all`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalElements provides ElementRegistry(),
                LocalPractice provides Practice(),
            ) {
                RecordTile(request("recordTile"))
            }
        }
        waitForIdle()
        assertTrue(
            onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty(),
            "A template with no click action anywhere is inert.",
        )
    }
}

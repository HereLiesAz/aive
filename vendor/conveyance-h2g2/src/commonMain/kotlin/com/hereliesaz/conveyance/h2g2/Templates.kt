package com.hereliesaz.conveyance.h2g2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.Act
import com.hereliesaz.conveyance.compose.Offer
import com.hereliesaz.conveyance.compose.tell
import com.hereliesaz.conveyance.h2g2.H2g2.contrastingText

// Every template in this registry attaches `Modifier.tell(owesTell, weight).clickable { engage() }`
// to its outermost shape -- the exact wiring Conveyance's own demo (conveyance-demo/.../Gallery.kt)
// uses at every real Offer call site. Without it a template still renders correctly but is inert:
// nothing engages the act on tap, so ActState can never leave Ready through this template alone.
//
// Every template also clips to `request.surfaceShape(<its own default>)` rather than to a
// hardcoded H2g2Surface literal, so the manifest's `surface` string actually selects the shape --
// the behaviour ComposableRequest.surface has always been documented to have.

/**
 * What a `kind: "composable"` `.azp` package's `elements[]` entry (azphalt `spec/composable.md`)
 * supplies once a host has resolved it against this library's [Templates.registry] and built the
 * live [Act] the element performs. The manifest's own `hue`/`surface`/`scale` strings pass
 * straight through as [hueSeed]/[surface]/[scale]; `templateId` is the registry lookup key and
 * isn't repeated here. [subtitle] is optional -- most templates render [label] alone; the ones
 * that use a second line say so. [detailLines] is likewise optional -- only
 * `h2g2.tile.record.detail` uses it. [endCapText], when present, renders trailing the label in
 * `H2g2.caps`' darker mate and the `endCap` type step -- `docs/DESIGN.md` §2's own "label and
 * end-cap sit together at the right end," with the "darker mate on the end-cap" coloring from
 * §7 ("Unchanged from Azphalt") layered on top, on the two templates whose names say `.capped`.
 */
data class ComposableRequest(
    val act: Act,
    /** Resolved via [H2g2.indexOf] -- a real hue name if it's one of [H2g2.hueNames], otherwise
     *  hashed via [H2g2.hueOf], typically off the manifest element's own `id` or a subject id. */
    val hueSeed: String,
    /**
     * One of [H2g2Surface]'s four names (`recordTile`/`note`/`well`/`capsule`), resolved through
     * [surfaceShape]. Every template clips its outermost shape to this; a name this vocabulary
     * doesn't have leaves that template on its own documented default shape.
     */
    val surface: String,
    val scale: String,
    val label: String,
    val subtitle: String? = null,
    val detailLines: List<String>? = null,
    val endCapText: String? = null,
)

/**
 * The single point every template resolves [ComposableRequest.surface] through.
 *
 * A manifest naming one of [H2g2Surface]'s four shapes gets that shape; anything else -- an empty
 * string, a name this vocabulary doesn't have, a manifest that simply didn't bother -- keeps
 * [default], the shape the calling template was written around. That keeps `h2g2.tile.note`
 * note-shaped and `h2g2.pill.action` capsule-shaped for every existing caller while making
 * [ComposableRequest.surface] do the thing it has always been documented to do.
 *
 * This exists as a named function rather than an inline `H2g2Surface.byName(...)` call in six
 * places so the resolution rule is testable on its own (`TemplatesTest`) without a UI harness:
 * an adversarial audit found every template hardcoding its shape literal and ignoring
 * [ComposableRequest.surface] entirely, and [H2g2Surface.byName] with no callers at all.
 */
fun ComposableRequest.surfaceShape(default: Shape): Shape = H2g2Surface.byName(surface, default)

/**
 * The h2g2 composable-set's template registry -- what a `templateId` resolves against once this
 * artifact is linked at build time. A host looks a `templateId` up here and calls the matching
 * function with the manifest's declared token values; nothing arrives that this artifact didn't
 * already ship.
 */
object Templates {
    val registry: Map<String, @Composable (ComposableRequest) -> Unit> = mapOf(
        "h2g2.tile.record" to { request -> RecordTile(request) },
        "h2g2.tile.record.detail" to { request -> RecordTileWithWell(request) },
        "h2g2.tile.record.capped" to { request -> CappedRecordTile(request) },
        "h2g2.tile.note" to { request -> NoteTile(request) },
        "h2g2.pill.action" to { request -> ActionPill(request) },
        "h2g2.pill.capped" to { request -> CappedPill(request) },
    )
}

/**
 * A [H2g2Surface.recordTile]-shaped element: the larger of h2g2's two soft rectangular surfaces,
 * colored by [ComposableRequest.hueSeed] via [H2g2.indexOf], offering [ComposableRequest.act].
 * Renders [ComposableRequest.subtitle] as a second, smaller line below [ComposableRequest.label]
 * when present -- a title plus a detail line, the record tile's two-line form.
 */
@Composable
fun RecordTile(request: ComposableRequest) {
    val hue = H2g2.hues[H2g2.indexOf(request.hueSeed)]
    val textColor = hue.contrastingText()
    val type = h2g2Type()
    val shape = request.surfaceShape(H2g2Surface.recordTile)
    Offer(act = request.act, modifier = Modifier.wrapContentSize()) {
        Box(
            modifier = Modifier
                .tell(owesTell, weight)
                .clickable { engage() }
                .clip(shape)
                .background(hue)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (request.subtitle == null) {
                BasicText(
                    text = request.label,
                    style = type.step(request.scale).copy(color = textColor),
                )
            } else {
                Column {
                    BasicText(
                        text = request.label,
                        style = type.step(request.scale).copy(color = textColor),
                    )
                    BasicText(
                        text = request.subtitle,
                        style = type.body.copy(color = textColor),
                    )
                }
            }
        }
    }
}

/**
 * A [H2g2Surface.recordTile] card with a nested [H2g2Surface.well] holding
 * [ComposableRequest.detailLines] -- the ink-background raw-output panel HG2Gui itself nests
 * inside a record tile for command results, network status, a context switch's confirmation
 * (`docs/HG2Gui Surfaces.dc.html`). Falls back to a labeled [H2g2Surface.recordTile] with no well
 * when [ComposableRequest.detailLines] is null or empty.
 */
@Composable
fun RecordTileWithWell(request: ComposableRequest) {
    val hue = H2g2.hues[H2g2.indexOf(request.hueSeed)]
    val textColor = hue.contrastingText()
    val lines = request.detailLines
    val type = h2g2Type()
    val shape = request.surfaceShape(H2g2Surface.recordTile)
    Offer(act = request.act, modifier = Modifier.wrapContentSize()) {
        Column(
            modifier = Modifier
                .tell(owesTell, weight)
                .clickable { engage() }
                .clip(shape)
                .background(hue)
                .padding(20.dp),
        ) {
            BasicText(
                text = request.label,
                style = type.step(request.scale).copy(color = textColor),
            )
            if (!lines.isNullOrEmpty()) {
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clip(H2g2Surface.well)
                        .background(H2g2.ink)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    lines.forEach { line ->
                        BasicText(text = line, style = type.body.copy(color = H2g2.white))
                    }
                }
            }
        }
    }
}

/**
 * A [H2g2Surface.note]-shaped element: h2g2's other soft rectangular surface -- smaller, meant
 * for a secondary/annotation block rather than a primary record. Unlike [RecordTile],
 * [ComposableRequest.subtitle] renders *above* [ComposableRequest.label] in the `eyebrow` step --
 * a note is usually labeled ("Reminder", "Note to self") before it's read, not captioned after.
 */
@Composable
fun NoteTile(request: ComposableRequest) {
    val hue = H2g2.hues[H2g2.indexOf(request.hueSeed)]
    val textColor = hue.contrastingText()
    val type = h2g2Type()
    val shape = request.surfaceShape(H2g2Surface.note)
    Offer(act = request.act, modifier = Modifier.wrapContentSize()) {
        Box(
            modifier = Modifier
                .tell(owesTell, weight)
                .clickable { engage() }
                .clip(shape)
                .background(hue)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                if (request.subtitle != null) {
                    BasicText(text = request.subtitle, style = type.eyebrow.copy(color = textColor))
                }
                BasicText(
                    text = request.label,
                    style = type.step(request.scale).copy(color = textColor),
                )
            }
        }
    }
}

/**
 * A [H2g2Surface.capsule]-shaped element: h2g2's default surface, used for nearly everything --
 * buttons, chips, pills -- colored by [ComposableRequest.hueSeed] via [H2g2.indexOf], offering
 * [ComposableRequest.act].
 */
@Composable
fun ActionPill(request: ComposableRequest) {
    val hue = H2g2.hues[H2g2.indexOf(request.hueSeed)]
    val shape = request.surfaceShape(H2g2Surface.capsule)
    Offer(act = request.act, modifier = Modifier.wrapContentSize()) {
        Box(
            modifier = Modifier
                .tell(owesTell, weight)
                .clickable { engage() }
                .clip(shape)
                .background(hue)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = request.label,
                style = h2g2Type().step(request.scale).copy(color = hue.contrastingText()),
            )
        }
    }
}

/**
 * A small capsule chip in [H2g2.caps]' darker mate of the surrounding hue, set in the `endCap`
 * type step -- the trailing element `docs/DESIGN.md` describes riding at the right end alongside
 * a label, never on its own.
 */
@Composable
private fun EndCap(text: String, hueIndex: Int) {
    val cap = H2g2.caps[hueIndex]
    Box(
        modifier = Modifier
            .clip(H2g2Surface.capsule)
            .background(cap)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        BasicText(text = text, style = h2g2Type().endCap.copy(color = cap.contrastingText()))
    }
}

/**
 * [ActionPill] with [ComposableRequest.endCapText] riding the right end in [H2g2.caps]' darker
 * mate -- `docs/DESIGN.md` §2's "label and end-cap sit together at the right end, in that order,"
 * read for a natural-width pill rather than the pill menu's own anchored/overhung ones. Falls
 * back to plain [ActionPill] layout when [ComposableRequest.endCapText] is null.
 */
@Composable
fun CappedPill(request: ComposableRequest) {
    val hueIndex = H2g2.indexOf(request.hueSeed)
    val hue = H2g2.hues[hueIndex]
    val shape = request.surfaceShape(H2g2Surface.capsule)
    Offer(act = request.act, modifier = Modifier.wrapContentSize()) {
        Row(
            modifier = Modifier
                .tell(owesTell, weight)
                .clickable { engage() }
                .clip(shape)
                .background(hue)
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = if (request.endCapText != null) 6.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = request.label,
                style = h2g2Type().step(request.scale).copy(color = hue.contrastingText()),
            )
            request.endCapText?.let {
                Box(modifier = Modifier.padding(start = 10.dp)) { EndCap(it, hueIndex) }
            }
        }
    }
}

/**
 * [RecordTile] with [ComposableRequest.endCapText] riding the tile's own right end in
 * [H2g2.caps]' darker mate -- the same end-cap vocabulary [CappedPill] uses, applied to the
 * record tile shape rather than the capsule. Falls back to plain [RecordTile] layout when
 * [ComposableRequest.endCapText] is null.
 */
@Composable
fun CappedRecordTile(request: ComposableRequest) {
    val hueIndex = H2g2.indexOf(request.hueSeed)
    val hue = H2g2.hues[hueIndex]
    val textColor = hue.contrastingText()
    val shape = request.surfaceShape(H2g2Surface.recordTile)
    Offer(act = request.act, modifier = Modifier.wrapContentSize()) {
        Row(
            modifier = Modifier
                .tell(owesTell, weight)
                .clickable { engage() }
                .clip(shape)
                .background(hue)
                .padding(start = 20.dp, top = 14.dp, bottom = 14.dp, end = if (request.endCapText != null) 10.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = request.label,
                style = h2g2Type().step(request.scale).copy(color = textColor),
            )
            request.endCapText?.let {
                Box(modifier = Modifier.padding(start = 12.dp)) { EndCap(it, hueIndex) }
            }
        }
    }
}

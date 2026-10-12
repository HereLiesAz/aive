package com.hereliesaz.conveyance.h2g2

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * h2g2's surface vocabulary. [recordTile]/[note] are the source-code-defined `AzphaltSurface`
 * (`ui/theme/AzphaltSurface.kt`); [well] is a fourth shape that object doesn't name but the
 * actual design mockups (`docs/HG2Gui Surfaces.dc.html`) use consistently -- an 18dp-radius,
 * ink-background container for raw/monospace output (command results, network status, a context
 * switch's confirmation), independent of whether it's nested inside a [recordTile] or standing on
 * its own. Everything else resolves to the fully-rounded [capsule]. There is deliberately no
 * sharp-corner "card" shape -- almost every surface in practice resolves to [capsule].
 *
 * [note] is not a lesser or unreconciled shape despite the file-explorer's own
 * `folderTileShape(isOpen, width)` (`ui/files/FilesScreen.kt`) picking between all three of
 * [recordTile]/[note]/[capsule] by state and size -- reading only `docs/DESIGN.md` (which is
 * scoped to the pill menu specifically, not the whole app) suggests otherwise, but the file
 * explorer's own code treats all three as equally deliberate.
 */
object H2g2Surface {
    val recordTile: Shape = RoundedCornerShape(26.dp)
    val note: Shape = RoundedCornerShape(20.dp)
    val well: Shape = RoundedCornerShape(18.dp)
    val capsule: Shape = RoundedCornerShape(percent = 50)

    /**
     * Looks up a shape by the composable manifest's `surface` string (azphalt
     * `spec/composable.md`), falling back to [capsule] -- h2g2's default surface -- for anything
     * that isn't one of the four names.
     */
    fun byName(name: String?): Shape = byName(name, capsule)

    /**
     * [byName] with a caller-chosen fallback, which is what every entry in [Templates.registry]
     * actually uses: a template that names no `surface` at all (or names one this vocabulary
     * doesn't have) keeps the shape that template was written around -- `h2g2.tile.note` stays a
     * [note], `h2g2.pill.action` stays a [capsule] -- rather than silently collapsing every
     * template to [capsule]. A manifest that *does* name a real surface overrides that default,
     * which is the behaviour [ComposableRequest.surface] was always documented to have.
     */
    fun byName(name: String?, default: Shape): Shape = when (name) {
        "recordTile" -> recordTile
        "note" -> note
        "well" -> well
        "capsule" -> capsule
        else -> default
    }
}

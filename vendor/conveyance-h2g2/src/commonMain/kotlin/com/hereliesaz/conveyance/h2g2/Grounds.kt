package com.hereliesaz.conveyance.h2g2

import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.hereliesaz.conveyance.h2g2.H2g2.contrastingText

/**
 * A named background theme: a flat [page] color plus a lighter [foldLight] and darker
 * [foldDark] variant of the same hue, painted as a crease down the middle of the screen rather
 * than a flat fill. Ported from HG2Gui's own `Azphalt.Ground`, values unchanged.
 */
data class Ground(
    val name: String,
    val page: Color,
    val foldLight: Color,
    val foldDark: Color,
    /** Relative odds of [randomGround] picking this ground. */
    val weight: Int = 1,
)

val grounds: List<Ground> = listOf(
    Ground("Mustard", Color(0xFFE8C81E), Color(0xFFF2D82C), Color(0xFFD9B615), weight = 6),
    Ground("Maroon", Color(0xFF8F1F34), Color(0xFFA22940), Color(0xFF7A1A2C)),
    Ground("Navy", Color(0xFF163A63), Color(0xFF204B7C), Color(0xFF0F2C4C)),
    Ground("Cerulean", Color(0xFF2D6EA8), Color(0xFF3C82C2), Color(0xFF215A8C)),
    Ground("Teal", Color(0xFF1D6B62), Color(0xFF267F74), Color(0xFF14554E)),
    Ground("Pink", Color(0xFFD4728F), Color(0xFFDD879F), Color(0xFFC15D7A)),
)

/** Weighted-random pick from [grounds], optionally excluding one (so a reroll can't repeat it). */
fun randomGround(exclude: Ground? = null): Ground {
    val pool = grounds.filter { it !== exclude }.ifEmpty { grounds }
    val total = pool.sumOf { it.weight }
    var roll = (0 until total).random()
    for (ground in pool) {
        roll -= ground.weight
        if (roll < 0) return ground
    }
    return pool.last()
}

private const val MAX_GROUND_REROLLS = 2

/**
 * The active [Ground] and its reroll budget, held for the lifetime of whatever owns this
 * instance (a host app decides that scope -- process-wide, per-screen, or per-composition).
 */
class GroundState(initial: Ground = randomGround()) {
    var current: Ground by mutableStateOf(initial)
        private set
    private var rerollsUsed = 0
    val canReroll: Boolean get() = rerollsUsed < MAX_GROUND_REROLLS

    fun reroll() {
        if (!canReroll) return
        current = randomGround(exclude = current)
        rerollsUsed++
    }
}

private const val FOLD_HIGHLIGHT_STOP_BEFORE = 0.46f
private const val FOLD_CENTER_STOP = 0.5f
private const val FOLD_HIGHLIGHT_STOP_AFTER = 0.54f

/** The five-stop fold-crease gradient this [Ground] paints as a background. */
fun Ground.pageBrush(): Brush = Brush.linearGradient(
    0f to page,
    FOLD_HIGHLIGHT_STOP_BEFORE to foldLight,
    FOLD_CENTER_STOP to foldDark,
    FOLD_HIGHLIGHT_STOP_AFTER to foldLight,
    1f to page,
)

/** Readable ink/white text color against this [Ground]'s [Ground.page]. */
val Ground.onPage: Color get() = page.contrastingText()

/** Paints [Ground.pageBrush] as this modifier's background. */
fun Modifier.ground(ground: Ground): Modifier = background(ground.pageBrush())

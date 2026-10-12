package com.hereliesaz.conveyance.demo

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.hereliesaz.conveyance.Rank

/**
 * This demo's local visual language.
 *
 * These choices are not Conveyance channel law. The framework provides semantic relationships and a
 * reference channel vocabulary; a product is free to establish another coherent visual grammar. In
 * this sample, a small rank palette is convenient compositional shorthand, while photographs use
 * their own identity-rich colour because their colour belongs to the content itself.
 */
object Look {
    val ground = Color(0xFF0B0C10)
    val ink = Color(0xFFF4F5F8)
    val quiet = Color(0xFF6E7482)

    /** Demo-local prominence colours. This does not mean Channel.Hue universally carries rank. */
    fun rank(rank: Rank): Color = when (rank) {
        Rank.Primary -> Color(0xFFFFC24B)
        Rank.Secondary -> Color(0xFF2A2F3A)
        Rank.Tertiary -> Color(0xFF171A21)
    }

    /** In this demo, chroma is used to show how recently/often a destination has been active. */
    fun heat(fraction: Float): Color =
        Color(0xFFFFC24B).copy(alpha = (0.10f + 0.75f * fraction).coerceIn(0f, 1f))

    /** Content identity rather than interface chrome. */
    fun photograph(seed: Int): Brush {
        val palettes = listOf(
            listOf(Color(0xFFEF6C5A), Color(0xFF8E2E58), Color(0xFF2B1B3D)),
            listOf(Color(0xFF6FD3C7), Color(0xFF2B7A9B), Color(0xFF16304A)),
            listOf(Color(0xFFF3C969), Color(0xFFCC7A2B), Color(0xFF4A2418)),
            listOf(Color(0xFFA8D06B), Color(0xFF3E8B5A), Color(0xFF13301F)),
            listOf(Color(0xFFB79BE8), Color(0xFF5C4A9B), Color(0xFF1E1A33)),
            listOf(Color(0xFF8FB4FF), Color(0xFF2F5BA8), Color(0xFF121C33)),
        )
        return Brush.linearGradient(palettes[seed.mod(palettes.size)])
    }
}

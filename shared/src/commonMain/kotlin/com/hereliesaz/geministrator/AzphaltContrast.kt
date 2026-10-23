package com.hereliesaz.geministrator

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/*
 * The one rule for colour in The Aive: content colour is always derived from the surface it is
 * drawn on. Nothing hard-codes a text, icon or glyph colour, and nothing trusts a theme default
 * that may not match; every container that paints a background also publishes it (see
 * [AzphaltSurface]), and every piece of content asks for the exact opposite of that surface.
 */

/** WCAG AA contrast for normal-size text. */
internal const val AZPHALT_MIN_TEXT_CONTRAST = 4.5f

/** WCAG AA contrast for large or bold text, icons and other glyphs, and for disabled or secondary text. */
internal const val AZPHALT_MIN_LARGE_CONTRAST = 3f

/** WCAG 2.x contrast ratio between two colours (1 to 21), from relative luminance. */
internal fun contrastRatio(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    val lighter = if (a > b) a else b
    val darker = if (a > b) b else a
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * The exact opposite of [background] for content drawn on it: pure black on a light surface, pure
 * white on a dark one, whichever has the higher WCAG contrast. For any opaque surface that is at
 * least 4.58:1, so it clears 4.5:1 for text and 3:1 for large text and icons, and it can never be
 * the background colour or a near match.
 *
 * A translucent [background] is first composited over [under] (the surface beneath it), because
 * what the eye sees, and what must stay readable, is the composite.
 */
internal fun oppositeOf(background: Color, under: Color = Azphalt.currentGround.page): Color {
    val surface = background.compositeOver(under)
    return if (contrastRatio(Color.Black, surface) >= contrastRatio(Color.White, surface)) Color.Black else Color.White
}

/** [oppositeOf] this colour as a background. */
internal fun Color.onOpposite(under: Color = Azphalt.currentGround.page): Color = oppositeOf(this, under)

/**
 * A softened [onOpposite] for disabled text, placeholders and other de-emphasised content: the
 * exact opposite blended toward the surface as far as it can go while still meeting [minRatio]
 * (3:1 by default, the floor for disabled and secondary text) against the surface. Falls back to
 * the full opposite, so the result never drops below [minRatio].
 */
internal fun Color.mutedOnOpposite(
    minRatio: Float = AZPHALT_MIN_LARGE_CONTRAST,
    under: Color = Azphalt.currentGround.page,
): Color {
    val surface = compositeOver(under)
    val full = surface.onOpposite(under)
    for (step in MUTE_STEPS) {
        val candidate = lerp(full, surface, step)
        if (contrastRatio(candidate, surface) >= minRatio) return candidate
    }
    return full
}

private val MUTE_STEPS = listOf(0.5f, 0.45f, 0.4f, 0.35f, 0.3f, 0.25f, 0.2f, 0.15f, 0.1f, 0.05f)

/** Kept for call sites that still say "contrasting text": it is [onOpposite]. */
internal val Color.contrastingText: Color
    get() = onOpposite()

/** The colour of the surface content is currently drawn on, or null to mean the page ground. */
internal val LocalAzphaltSurface = staticCompositionLocalOf<Color?> { null }

/** The surface the current content sits on: the nearest [AzphaltSurface], else the page ground. */
@Composable
internal fun azphaltSurface(): Color = LocalAzphaltSurface.current ?: Azphalt.currentGround.page

/** The exact opposite of [azphaltSurface]: the colour for text, icons and glyphs drawn here. */
@Composable
internal fun azphaltOnSurface(): Color = azphaltSurface().onOpposite()

/** Disabled or secondary content on [azphaltSurface]: softened, but never under 3:1. */
@Composable
internal fun azphaltMutedOnSurface(): Color = azphaltSurface().mutedOnOpposite()

/**
 * Declares that everything inside is drawn on [background] (composited over the surface already
 * published, so an alpha background resolves to what is really seen). It publishes that surface
 * and sets [LocalContentColor] to its exact opposite, so a bare `Text` or `Icon` inside is legible
 * too. Use this around every container that paints its own background. When the content includes
 * Material components (text fields, menus, checkboxes) use [AzphaltThemedSurface] instead.
 */
@Composable
internal fun AzphaltSurface(background: Color, content: @Composable () -> Unit) {
    val parent = azphaltSurface()
    val surface = background.compositeOver(parent)
    CompositionLocalProvider(
        LocalAzphaltSurface provides surface,
        LocalContentColor provides surface.onOpposite(),
        content = content,
    )
}

/**
 * [AzphaltSurface] plus a Material colour scheme built for that surface, so default-coloured
 * Material components inside (outlined text fields, dropdown menus, checkboxes, text buttons)
 * take their text, outline, container and accent colours from the surface they are on.
 */
@Composable
internal fun AzphaltThemedSurface(background: Color, content: @Composable () -> Unit) {
    val parent = azphaltSurface()
    val surface = background.compositeOver(parent)
    val scheme = remember(surface) { azphaltColorScheme(surface) }
    CompositionLocalProvider(
        LocalAzphaltSurface provides surface,
        LocalContentColor provides surface.onOpposite(),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * A Material colour scheme for content on [surface]: every surface role is [surface], every "on"
 * role is its exact opposite, and the accent roles (primary and friends) are that opposite too,
 * because an accent drawn on [surface] must be readable on it. Error is the same: the tone of an
 * error is carried by its text, not by a colour that may vanish on the ground.
 */
internal fun azphaltColorScheme(surface: Color): ColorScheme {
    val on = surface.onOpposite(under = surface)
    val outline = surface.mutedOnOpposite(AZPHALT_MIN_LARGE_CONTRAST, under = surface)
    return darkColorScheme(
        primary = on,
        onPrimary = surface,
        primaryContainer = surface,
        onPrimaryContainer = on,
        inversePrimary = on,
        secondary = on,
        onSecondary = surface,
        secondaryContainer = surface,
        onSecondaryContainer = on,
        tertiary = on,
        onTertiary = surface,
        tertiaryContainer = surface,
        onTertiaryContainer = on,
        background = surface,
        onBackground = on,
        surface = surface,
        onSurface = on,
        surfaceVariant = surface,
        onSurfaceVariant = on,
        surfaceTint = surface,
        inverseSurface = on,
        inverseOnSurface = surface,
        error = on,
        onError = surface,
        errorContainer = surface,
        onErrorContainer = on,
        outline = outline,
        outlineVariant = outline,
        scrim = Color.Black,
        surfaceBright = surface,
        surfaceDim = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        surfaceContainerLow = surface,
        surfaceContainerLowest = surface,
    )
}

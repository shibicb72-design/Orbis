package com.shibi.orbis.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The few colours a theme is made of for one of light or dark: [primary] is the accent, [background] the page of
 * Orbis's own screens and [ink] the text colour. Everything else is derived by [paletteMode].
 */
internal class Palette(val primary: Color, val background: Color, val ink: Color)

/**
 * Tertiary is reserved for private tabs (the tabs-count icon, the private banner and unselected private tab
 * chips). Every theme uses these exact colours for it and none uses plum for anything else.
 */
internal object PrivateColors {
    val Light = Triple(Color(0xFF765265), Color(0xFFF0DFE8), Color(0xFF442536)) // tertiary, container, onContainer
    val OnTertiaryLight = Color.White
    val Dark = Triple(Color(0xFFDCB7CB), Color(0xFF5A3B4D), Color(0xFFF0DFE8))
    val OnTertiaryDark = Color(0xFF432535)
}

/** Builds a whole Material colour scheme from a [Palette]; the surface ladder is a lerp from background toward ink. */
internal fun paletteMode(p: Palette, dark: Boolean, inversePrimary: Color): ThemeMode {
    val bg = p.background
    val ink = p.ink

    // Light surfaces lean a little toward the accent, so the plum private chip never sits on a same-hue grey.
    val tone = if (dark) ink else lerp(ink, p.primary, 0.5f)
    fun toInk(t: Float) = lerp(bg, tone, t)
    val lowest: Color
    val surface: Color
    val low: Color
    val container: Color
    val high: Color
    val highest: Color
    val variant: Color
    val bright: Color
    val dim: Color
    if (dark) {
        lowest = lerp(bg, Color.Black, 0.4f)
        surface = toInk(0.03f)
        low = surface
        container = toInk(0.06f)
        high = toInk(0.10f)
        highest = toInk(0.14f)
        variant = toInk(0.12f)
        bright = highest
        dim = bg
    } else {
        lowest = Color.White
        surface = Color.White
        low = toInk(0.02f)
        container = toInk(0.05f)
        high = toInk(0.08f)
        highest = toInk(0.11f)
        variant = high
        bright = Color.White
        dim = toInk(0.14f)
    }
    val surfaces = listOf(bg, surface, low, container, high, highest, variant)
    val onSurfaceVariant = readableOn(lerp(ink, bg, 0.25f), surfaces, ink)
    val primary = readableOn(p.primary, surfaces, ink)
    val onPrimary = bestOn(primary, listOf(Color.White, bg, ink))
    val primaryContainer = lerp(bg, primary, if (dark) 0.30f else 0.22f)
    val onPrimaryContainer = bestOn(primaryContainer, listOf(ink, Color.White))
    val secondary = readableOn(lerp(primary, ink, if (dark) 0.15f else 0.45f), surfaces, ink)
    val onSecondary = bestOn(secondary, listOf(Color.White, bg, ink))
    val secondaryContainer = lerp(bg, primary, if (dark) 0.24f else 0.18f)
    val onSecondaryContainer = bestOn(secondaryContainer, listOf(ink, Color.White))
    val outline = readableOn(toInk(0.55f), listOf(surface, bg, container), ink, min = 3.2)
    val outlineVariant = toInk(if (dark) 0.22f else 0.18f)
    val inverseSurface = lerp(ink, bg, 0.1f)
    val errors = if (dark) TealDark else TealLight

    val scheme =
        if (dark) {
            darkColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = onSecondary,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = PrivateColors.Dark.first,
                onTertiary = PrivateColors.OnTertiaryDark,
                tertiaryContainer = PrivateColors.Dark.second,
                onTertiaryContainer = PrivateColors.Dark.third,
                background = bg,
                onBackground = ink,
                surface = surface,
                onSurface = ink,
                surfaceVariant = variant,
                onSurfaceVariant = onSurfaceVariant,
                surfaceContainerLowest = lowest,
                surfaceContainerLow = low,
                surfaceContainer = container,
                surfaceContainerHigh = high,
                surfaceContainerHighest = highest,
                surfaceBright = bright,
                surfaceDim = dim,
                inverseSurface = inverseSurface,
                inverseOnSurface = bg,
                inversePrimary = inversePrimary,
                surfaceTint = primary,
                outline = outline,
                outlineVariant = outlineVariant,
                error = errors.error,
                onError = errors.onError,
                errorContainer = errors.errorContainer,
                onErrorContainer = errors.onErrorContainer,
            )
        } else {
            lightColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = onSecondary,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = PrivateColors.Light.first,
                onTertiary = PrivateColors.OnTertiaryLight,
                tertiaryContainer = PrivateColors.Light.second,
                onTertiaryContainer = PrivateColors.Light.third,
                background = bg,
                onBackground = ink,
                surface = surface,
                onSurface = ink,
                surfaceVariant = variant,
                onSurfaceVariant = onSurfaceVariant,
                surfaceContainerLowest = lowest,
                surfaceContainerLow = low,
                surfaceContainer = container,
                surfaceContainerHigh = high,
                surfaceContainerHighest = highest,
                surfaceBright = bright,
                surfaceDim = dim,
                inverseSurface = inverseSurface,
                inverseOnSurface = bg,
                inversePrimary = inversePrimary,
                surfaceTint = primary,
                outline = outline,
                outlineVariant = outlineVariant,
                error = errors.error,
                onError = errors.onError,
                errorContainer = errors.errorContainer,
                onErrorContainer = errors.onErrorContainer,
            )
        }
    return ThemeMode(scheme)
}

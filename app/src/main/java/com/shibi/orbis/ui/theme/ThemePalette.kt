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

/** Light surfaces keep at least this OKLab distance from the private plum chip (Orbis Teal measures 0.044). */
private const val MIN_PRIVATE_TO_CONTAINER = 0.046
private const val MIN_PRIVATE_TO_LOW = 0.055

/**
 * Moves a light surface away from the private plum, in steps along the line from plum through it, until it is [min]
 * apart in OKLab. A plum chip must stay visible against the surfaces a tab sits on (Orbis Teal gets this from its
 * cyan tint). Light themes only.
 */
private fun awayFromPlum(c: Color, min: Double): Color {
    val plum = PrivateColors.Light.second
    for (k in 0..40) {
        val x = Color(
            red = (c.red + (c.red - plum.red) * k * 0.1f).coerceIn(0f, 1f),
            green = (c.green + (c.green - plum.green) * k * 0.1f).coerceIn(0f, 1f),
            blue = (c.blue + (c.blue - plum.blue) * k * 0.1f).coerceIn(0f, 1f),
        )
        if (okLabDistance(plum, x) >= min) return x
    }
    return c
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
        low = awayFromPlum(toInk(0.02f), MIN_PRIVATE_TO_LOW)
        container = awayFromPlum(toInk(0.05f), MIN_PRIVATE_TO_CONTAINER)
        high = toInk(0.08f)
        highest = toInk(0.11f)
        variant = high
        bright = Color.White
        dim = toInk(0.14f)
    }
    val surfaces = listOf(bg, surface, low, container, high, highest, variant)
    val primary = readableOn(p.primary, surfaces, ink)
    // The accent must read on its own tinted containers (dashboard buttons, selected tabs): thin the tint until it does.
    fun tinted(start: Float): Color {
        var t = start
        while (t > 0.02f) {
            val c = lerp(bg, primary, t)
            if (contrastOf(primary, c) >= 4.55) return c
            t -= 0.02f
        }
        return lerp(bg, primary, 0.02f)
    }
    val onPrimary = bestOn(primary, listOf(Color.White, bg, ink))
    val primaryContainer = tinted(if (dark) 0.30f else 0.22f)
    val onSurfaceVariant = readableOn(lerp(ink, bg, 0.25f), surfaces + primaryContainer, ink)
    val onPrimaryContainer = bestOn(primaryContainer, listOf(ink, Color.White))
    val secondary = readableOn(lerp(primary, ink, if (dark) 0.15f else 0.45f), surfaces, ink)
    val onSecondary = bestOn(secondary, listOf(Color.White, bg, ink))
    val secondaryContainer = tinted(if (dark) 0.24f else 0.18f)
    val onSecondaryContainer = bestOn(secondaryContainer, listOf(ink, Color.White))
    val outline = readableOn(toInk(0.55f), listOf(surface, bg, container), ink, min = 3.2)
    val outlineVariant = toInk(if (dark) 0.22f else 0.18f)
    val inverseSurface = lerp(ink, bg, 0.1f)
    // Snackbar action labels: the other mode's accent, moved toward the page colour until it reads on the snackbar.
    val inversePrimaryReadable = readableOn(inversePrimary, listOf(inverseSurface), bg)
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
                inversePrimary = inversePrimaryReadable,
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
                inversePrimary = inversePrimaryReadable,
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

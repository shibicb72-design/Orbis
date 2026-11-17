package com.shibi.orbis.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.shibi.orbis.R

/**
 * The Material You theme: the colours come from the phone's wallpaper, so they cannot be checked at build time.
 * Orbis fixes the private-tab plum (tertiary roles) as in every other theme; the chip background may move up to 15%
 * toward the darker plum so it stays at least 0.044 OKLab from the surfaces. Wallpaper surfaces are never changed.
 */

/** Stored id; never change it. Its light and dark hold the Orbis Teal palettes: the fallback, and what a preview without a context shows. */
internal val MaterialYouTheme =
    AppTheme(
        id = "material_you",
        nameRes = R.string.theme_name_material_you,
        descriptionRes = R.string.theme_desc_material_you,
        light = TealTheme.light,
        dark = TealTheme.dark,
        dynamic = true,
    )

/** Orbis Teal's weakest light gap between the plum chip and a neighbour (0.044); the dynamic theme keeps at least this. */
internal const val MIN_DYNAMIC_PRIVATE_GAP = 0.044

/** The most the private chip is moved toward the darker plum (tertiary) to clear the surfaces. */
internal const val MAX_CHIP_SHIFT = 0.15f

/** The system's wallpaper scheme, or null when the phone cannot give one (the caller then uses the Orbis Teal fallback). */
internal fun dynamicBaseScheme(context: Context, dark: Boolean): ColorScheme? =
    runCatching { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }.getOrNull()

/**
 * The private chip background: [plum] moved toward [tertiary] in 1% steps until it is [min] away from every one of
 * [surfaces], at most [MAX_CHIP_SHIFT]. The wallpaper's own surfaces are never changed.
 */
internal fun chipClearOf(plum: Color, tertiary: Color, surfaces: List<Color>, min: Double): Color {
    var t = 0f
    while (true) {
        val chip = if (t == 0f) plum else lerp(plum, tertiary, t)
        if (surfaces.all { okLabDistance(chip, it) >= min } || t >= MAX_CHIP_SHIFT) return chip
        t = (t + 0.01f).coerceAtMost(MAX_CHIP_SHIFT)
    }
}

/**
 * [base] with Orbis's private-tab colours in the tertiary roles. The wallpaper's surfaces stay exactly as the system
 * gives them; only the chip background (tertiaryContainer) moves, a little, so it stays apart from them.
 */
internal fun withOrbisPrivateLook(base: ColorScheme, dark: Boolean): ColorScheme {
    val plum = if (dark) PrivateColors.Dark.second else PrivateColors.Light.second
    val tertiary = if (dark) PrivateColors.Dark.first else PrivateColors.Light.first
    val chip =
        chipClearOf(
            plum,
            tertiary,
            listOf(base.surface, base.background, base.surfaceContainer, base.surfaceContainerLow),
            MIN_DYNAMIC_PRIVATE_GAP,
        )
    return base.copy(
        tertiary = tertiary,
        onTertiary = if (dark) PrivateColors.OnTertiaryDark else PrivateColors.OnTertiaryLight,
        tertiaryContainer = chip,
        onTertiaryContainer = if (dark) PrivateColors.Dark.third else PrivateColors.Light.third,
    )
}

/** The wallpaper scheme as Orbis shows it, or [fallback] when the system gives none. */
internal fun dynamicScheme(context: Context, dark: Boolean, fallback: ColorScheme): ColorScheme =
    dynamicBaseScheme(context, dark)?.let { withOrbisPrivateLook(it, dark) } ?: fallback

/**
 * The colour scheme to draw [this] theme with: its own palette, or for a [AppTheme.dynamic] theme the wallpaper's
 * (re-read when the configuration changes, which is how Android reports a new wallpaper palette).
 */
@Composable
fun AppTheme.rememberScheme(dark: Boolean): ColorScheme {
    val own = mode(dark).scheme
    if (!dynamic) return own
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, dark, own) { dynamicScheme(context, dark, own) }
}

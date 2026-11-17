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
 * Two things are therefore fixed by Orbis, as in every other theme: the private-tab plum (tertiary roles) and a
 * minimum OKLab distance between that plum and the surfaces a tab chip can sit on.
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

/** The most a surface is moved toward the text colour to clear the plum, so it keeps the wallpaper's look. */
private const val MAX_PLUM_NUDGE = 0.2f

/** The system's wallpaper scheme, or null when the phone cannot give one (the caller then uses the Orbis Teal fallback). */
internal fun dynamicBaseScheme(context: Context, dark: Boolean): ColorScheme? =
    runCatching { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }.getOrNull()

/** [color] moved toward [toward] in the smallest 1% steps until it is [min] away from [plum] (at most [MAX_PLUM_NUDGE]). */
private fun clearOf(plum: Color, color: Color, toward: Color, min: Double): Color {
    if (okLabDistance(plum, color) >= min) return color
    var t = 0.01f
    while (t <= MAX_PLUM_NUDGE) {
        val moved = lerp(color, toward, t)
        if (okLabDistance(plum, moved) >= min) return moved
        t += 0.01f
    }
    return lerp(color, toward, MAX_PLUM_NUDGE)
}

/** [base] with Orbis's private-tab plum in the tertiary roles, and the tab-chip neighbours kept clear of it. */
internal fun withOrbisPrivateLook(base: ColorScheme, dark: Boolean): ColorScheme {
    val container = if (dark) PrivateColors.Dark.second else PrivateColors.Light.second
    fun clear(c: Color) = clearOf(container, c, base.onSurface, MIN_DYNAMIC_PRIVATE_GAP)
    return base.copy(
        tertiary = if (dark) PrivateColors.Dark.first else PrivateColors.Light.first,
        onTertiary = if (dark) PrivateColors.OnTertiaryDark else PrivateColors.OnTertiaryLight,
        tertiaryContainer = container,
        onTertiaryContainer = if (dark) PrivateColors.Dark.third else PrivateColors.Light.third,
        surfaceContainer = clear(base.surfaceContainer),
        surfaceContainerLow = clear(base.surfaceContainerLow),
        surface = clear(base.surface),
        background = clear(base.background),
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

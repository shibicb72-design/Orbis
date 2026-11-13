package com.shibi.orbis.ui.theme

import androidx.compose.ui.graphics.Color
import com.shibi.orbis.R
import com.shibi.orbis.settings.DEFAULT_THEME_ID

private fun staticTheme(
    id: String,
    nameRes: Int,
    descriptionRes: Int,
    light: Palette,
    dark: Palette,
) = AppTheme(
    id = id,
    nameRes = nameRes,
    descriptionRes = descriptionRes,
    light = paletteMode(light, dark = false, inversePrimary = dark.primary),
    dark = paletteMode(dark, dark = true, inversePrimary = light.primary),
)

/** Today's look, unchanged: its two schemes are written out by hand in Theme.kt. */
internal val TealTheme =
    AppTheme(
        id = DEFAULT_THEME_ID,
        nameRes = R.string.theme_name_orbis_teal,
        descriptionRes = R.string.theme_desc_orbis_teal,
        light = ThemeMode(TealLight),
        dark = ThemeMode(TealDark),
    )

internal val GraphiteTheme =
    staticTheme(
        id = "graphite",
        nameRes = R.string.theme_name_graphite,
        descriptionRes = R.string.theme_desc_graphite,
        light = Palette(Color(0xFF3D4A56), Color(0xFFF5F7F7), Color(0xFF14191E)),
        dark = Palette(Color(0xFFB9C6D2), Color(0xFF0D0F11), Color(0xFFF1F4F6)),
    )

internal val HarborTheme =
    staticTheme(
        id = "harbor",
        nameRes = R.string.theme_name_harbor,
        descriptionRes = R.string.theme_desc_harbor,
        light = Palette(Color(0xFF2B5CA6), Color(0xFFF4F7FC), Color(0xFF0F1B2D)),
        dark = Palette(Color(0xFFA9C7FF), Color(0xFF0C1320), Color(0xFFEAF0FA)),
    )

internal val FernTheme =
    staticTheme(
        id = "fern",
        nameRes = R.string.theme_name_fern,
        descriptionRes = R.string.theme_desc_fern,
        light = Palette(Color(0xFF35683A), Color(0xFFF3F7F1), Color(0xFF111C13)),
        dark = Palette(Color(0xFFA6D49C), Color(0xFF0D160E), Color(0xFFECF4EA)),
    )

internal val EmberTheme =
    staticTheme(
        id = "ember",
        nameRes = R.string.theme_name_ember,
        descriptionRes = R.string.theme_desc_ember,
        light = Palette(Color(0xFF9A4518), Color(0xFFFBF6F0), Color(0xFF2A1A10)),
        dark = Palette(Color(0xFFFFB68E), Color(0xFF1A110C), Color(0xFFF8EDE6)),
    )

internal val PaperTheme =
    staticTheme(
        id = "paper",
        nameRes = R.string.theme_name_paper,
        descriptionRes = R.string.theme_desc_paper,
        light = Palette(Color(0xFF6E5426), Color(0xFFF7F4E1), Color(0xFF221B10)),
        dark = Palette(Color(0xFFE0C38C), Color(0xFF17130D), Color(0xFFF4ECDD)),
    )

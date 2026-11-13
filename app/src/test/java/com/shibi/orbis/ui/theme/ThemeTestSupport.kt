package com.shibi.orbis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Every role Orbis sets, by name. */
internal fun ColorScheme.roles(): List<Pair<String, Color>> =
    listOf(
        "primary" to primary,
        "onPrimary" to onPrimary,
        "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer,
        "secondary" to secondary,
        "onSecondary" to onSecondary,
        "secondaryContainer" to secondaryContainer,
        "onSecondaryContainer" to onSecondaryContainer,
        "tertiary" to tertiary,
        "onTertiary" to onTertiary,
        "tertiaryContainer" to tertiaryContainer,
        "onTertiaryContainer" to onTertiaryContainer,
        "error" to error,
        "onError" to onError,
        "errorContainer" to errorContainer,
        "onErrorContainer" to onErrorContainer,
        "background" to background,
        "onBackground" to onBackground,
        "surface" to surface,
        "onSurface" to onSurface,
        "surfaceVariant" to surfaceVariant,
        "onSurfaceVariant" to onSurfaceVariant,
        "surfaceContainerLowest" to surfaceContainerLowest,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
        "surfaceBright" to surfaceBright,
        "surfaceDim" to surfaceDim,
        "inverseSurface" to inverseSurface,
        "inverseOnSurface" to inverseOnSurface,
        "inversePrimary" to inversePrimary,
        "surfaceTint" to surfaceTint,
        "outline" to outline,
        "outlineVariant" to outlineVariant,
    )

internal fun hex(c: Color): String = "#" + Integer.toHexString(c.toArgb()).uppercase().padStart(8, '0')

/** One "id.mode.role=#AARRGGBB" line per colour, roles sorted by name. */
internal fun dump(theme: AppTheme): List<String> =
    listOf("light" to theme.light, "dark" to theme.dark).flatMap { (mode, m) ->
        m.scheme.roles().sortedBy { it.first }.map { (role, c) -> "${theme.id}.$mode.$role=${hex(c)}" }
    }

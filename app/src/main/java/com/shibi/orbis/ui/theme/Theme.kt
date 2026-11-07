package com.shibi.orbis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF216969)
private val Deep = Color(0xFF1B3A4B)
private val Cream = Color(0xFFF4F7F7)
private val Ink = Color(0xFF122028)

private val LightColors =
    lightColorScheme(
        primary = Teal,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCCE8E6),
        onPrimaryContainer = Color(0xFF123B3B),
        secondary = Deep,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCE9EE),
        onSecondaryContainer = Color(0xFF183440),
        tertiary = Color(0xFF765265),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF0DFE8),
        onTertiaryContainer = Color(0xFF442536),
        background = Cream,
        onBackground = Ink,
        surface = Color.White,
        onSurface = Ink,
        surfaceVariant = Color(0xFFE4ECEC),
        onSurfaceVariant = Color(0xFF435455),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF0F5F5),
        surfaceContainer = Color(0xFFEAF1F1),
        surfaceContainerHigh = Color(0xFFE4ECEC),
        surfaceContainerHighest = Color(0xFFDDE6E6),
        surfaceBright = Color.White,
        surfaceDim = Color(0xFFD6DFDF),
        inverseSurface = Color(0xFF25353B),
        inverseOnSurface = Cream,
        inversePrimary = Color(0xFF9AD3CF),
        surfaceTint = Teal,
        outline = Color(0xFF6F8080),
        outlineVariant = Color(0xFFC4D1D1),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF9AD3CF),
        onPrimary = Color(0xFF003736),
        primaryContainer = Color(0xFF1B5050),
        onPrimaryContainer = Color(0xFFCCE8E6),
        secondary = Color(0xFF8FB8B8),
        onSecondary = Deep,
        secondaryContainer = Color(0xFF304A57),
        onSecondaryContainer = Color(0xFFDCE9EE),
        tertiary = Color(0xFFDCB7CB),
        onTertiary = Color(0xFF432535),
        tertiaryContainer = Color(0xFF5A3B4D),
        onTertiaryContainer = Color(0xFFF0DFE8),
        background = Color(0xFF0E1A20),
        onBackground = Cream,
        surface = Color(0xFF152329),
        onSurface = Cream,
        surfaceVariant = Color(0xFF314247),
        onSurfaceVariant = Color(0xFFBFCDCF),
        surfaceContainerLowest = Color(0xFF0A151A),
        surfaceContainerLow = Color(0xFF152329),
        surfaceContainer = Color(0xFF1A2A30),
        surfaceContainerHigh = Color(0xFF25353B),
        surfaceContainerHighest = Color(0xFF304147),
        surfaceBright = Color(0xFF304147),
        surfaceDim = Color(0xFF0E1A20),
        inverseSurface = Color(0xFFE4ECEC),
        inverseOnSurface = Ink,
        inversePrimary = Teal,
        surfaceTint = Color(0xFF9AD3CF),
        outline = Color(0xFF899B9E),
        outlineVariant = Color(0xFF405257),
    )

@Composable
fun OrbisTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

package com.shibi.orbis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF3D8B8B)
private val Deep = Color(0xFF1B3A4B)
private val Cream = Color(0xFFF4F7F7)
private val Ink = Color(0xFF122028)

private val LightColors =
    lightColorScheme(
        primary = Teal,
        onPrimary = Color.White,
        secondary = Deep,
        onSecondary = Color.White,
        background = Cream,
        onBackground = Ink,
        surface = Color.White,
        onSurface = Ink,
    )

private val DarkColors =
    darkColorScheme(
        primary = Teal,
        onPrimary = Color.White,
        secondary = Color(0xFF8FB8B8),
        onSecondary = Deep,
        background = Color(0xFF0E1A20),
        onBackground = Cream,
        surface = Deep,
        onSurface = Cream,
    )

@Composable
fun OrbisTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
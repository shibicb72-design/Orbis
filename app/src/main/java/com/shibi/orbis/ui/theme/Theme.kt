package com.shibi.orbis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import com.shibi.orbis.settings.DEFAULT_THEME_ID
import com.shibi.orbis.settings.DarkMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF216969)
private val Deep = Color(0xFF1B3A4B)
private val Cream = Color(0xFFF4F7F7)
private val Ink = Color(0xFF122028)

internal val TealLight =
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

internal val TealDark =
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

/** One palette of a [AppTheme]: a complete Material colour scheme. */
@Immutable
class ThemeMode(val scheme: ColorScheme)

/**
 * A colour theme: a light and a dark palette for Orbis's own screens. Themes never touch web pages, layout or
 * behaviour. [animated] is for the animated themes of a later change; no theme is animated yet.
 */
@Immutable
class AppTheme(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    val light: ThemeMode,
    val dark: ThemeMode,
    val animated: Boolean = false,
) {
    fun mode(dark: Boolean): ThemeMode = if (dark) this.dark else light
}

object AppThemes {
    const val DEFAULT_ID = DEFAULT_THEME_ID

    val Default: AppTheme get() = TealTheme

    /** In picker order. Ids are stored, so never change one. */
    val all: List<AppTheme> by lazy { listOf(TealTheme, GraphiteTheme, HarborTheme, FernTheme, EmberTheme, PaperTheme) }

    /** An unknown or missing id (a downgrade, a removed theme) reads as Orbis Teal; the stored id is left alone. */
    fun byId(id: String?): AppTheme = all.firstOrNull { it.id == id } ?: Default
}

val LocalAppTheme = staticCompositionLocalOf { AppThemes.Default }

/** Whether the current theme's animated scene may run (always false until animated themes exist). */
val LocalThemeAnimate = staticCompositionLocalOf { false }

/** Whether [mode] is dark right now. [systemDark] is the system setting. */
fun DarkMode.isDark(systemDark: Boolean): Boolean =
    when (this) {
        DarkMode.SYSTEM -> systemDark
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }

@Composable
fun OrbisTheme(
    darkMode: DarkMode = DarkMode.SYSTEM,
    theme: AppTheme = AppThemes.Default,
    animate: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = darkMode.isDark(isSystemInDarkTheme())
    // Status and navigation bar icons follow the app theme, not the system's (they would vanish on a dark toolbar).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val bars = androidx.core.view.WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalAppTheme provides theme, LocalThemeAnimate provides animate) {
        MaterialTheme(
            colorScheme = theme.mode(dark).scheme,
            content = content,
        )
    }
}

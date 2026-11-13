package com.shibi.orbis.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Each colour role of [from] moved [t] of the way to [to]; t = 0 is exactly [from], t = 1 exactly [to]. */
internal fun lerpScheme(from: ColorScheme, to: ColorScheme, t: Float): ColorScheme {
    // lerp goes through OKLab, so even equal ends can come back a bit off. The ends and shared colours (the private
    // plum, which every theme keeps) are taken as they are.
    fun mix(a: Color, b: Color): Color =
        when {
            t <= 0f -> a
            t >= 1f || a == b -> b
            else -> lerp(a, b, t)
        }
    return to.copy(
        primary = mix(from.primary, to.primary),
        onPrimary = mix(from.onPrimary, to.onPrimary),
        primaryContainer = mix(from.primaryContainer, to.primaryContainer),
        onPrimaryContainer = mix(from.onPrimaryContainer, to.onPrimaryContainer),
        inversePrimary = mix(from.inversePrimary, to.inversePrimary),
        secondary = mix(from.secondary, to.secondary),
        onSecondary = mix(from.onSecondary, to.onSecondary),
        secondaryContainer = mix(from.secondaryContainer, to.secondaryContainer),
        onSecondaryContainer = mix(from.onSecondaryContainer, to.onSecondaryContainer),
        tertiary = mix(from.tertiary, to.tertiary),
        onTertiary = mix(from.onTertiary, to.onTertiary),
        tertiaryContainer = mix(from.tertiaryContainer, to.tertiaryContainer),
        onTertiaryContainer = mix(from.onTertiaryContainer, to.onTertiaryContainer),
        background = mix(from.background, to.background),
        onBackground = mix(from.onBackground, to.onBackground),
        surface = mix(from.surface, to.surface),
        onSurface = mix(from.onSurface, to.onSurface),
        surfaceVariant = mix(from.surfaceVariant, to.surfaceVariant),
        onSurfaceVariant = mix(from.onSurfaceVariant, to.onSurfaceVariant),
        surfaceTint = mix(from.surfaceTint, to.surfaceTint),
        inverseSurface = mix(from.inverseSurface, to.inverseSurface),
        inverseOnSurface = mix(from.inverseOnSurface, to.inverseOnSurface),
        error = mix(from.error, to.error),
        onError = mix(from.onError, to.onError),
        errorContainer = mix(from.errorContainer, to.errorContainer),
        onErrorContainer = mix(from.onErrorContainer, to.onErrorContainer),
        outline = mix(from.outline, to.outline),
        outlineVariant = mix(from.outlineVariant, to.outlineVariant),
        scrim = mix(from.scrim, to.scrim),
        surfaceBright = mix(from.surfaceBright, to.surfaceBright),
        surfaceDim = mix(from.surfaceDim, to.surfaceDim),
        surfaceContainer = mix(from.surfaceContainer, to.surfaceContainer),
        surfaceContainerHigh = mix(from.surfaceContainerHigh, to.surfaceContainerHigh),
        surfaceContainerHighest = mix(from.surfaceContainerHighest, to.surfaceContainerHighest),
        surfaceContainerLow = mix(from.surfaceContainerLow, to.surfaceContainerLow),
        surfaceContainerLowest = mix(from.surfaceContainerLowest, to.surfaceContainerLowest),
    )
}

/** One glide from [from] to [to]. A finished fade shows [to] itself. */
private class SchemeFade(val from: ColorScheme, val to: ColorScheme) {
    val fraction = Animatable(if (from === to) 1f else 0f)

    fun shown(): ColorScheme = if (fraction.value >= 1f) to else lerpScheme(from, to, fraction.value)
}

/** The fade that was last committed to the screen. */
private class LastFade(var fade: SchemeFade? = null)

/**
 * Re-provides MaterialTheme with a colour scheme that glides to the current one over THEME_CROSSFADE_MS.
 * With [enabled] false the current scheme passes straight through, with no extra work.
 */
@Composable
fun ThemeFade(enabled: Boolean, content: @Composable () -> Unit) {
    val target = MaterialTheme.colorScheme
    val last = remember { LastFade() }
    // A new target starts from the scheme on screen at this moment, so a change mid-fade does not jump.
    val fade = remember(target) { SchemeFade(from = last.fade?.takeIf { enabled }?.shown() ?: target, to = target) }
    SideEffect { last.fade = fade }
    LaunchedEffect(fade, enabled) {
        if (enabled) fade.fraction.animateTo(1f, motionTween(true, THEME_CROSSFADE_MS)) else fade.fraction.snapTo(1f)
    }
    // Decided here, not in the effect, so turning motion off (or reaching the browser) shows the target on the next
    // frame. With no fade running this is the target object itself, so nothing below recomposes.
    val scheme = if (enabled) fade.shown() else target
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

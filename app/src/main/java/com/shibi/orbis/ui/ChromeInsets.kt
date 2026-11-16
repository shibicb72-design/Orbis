package com.shibi.orbis.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/**
 * The insets Orbis's own screens keep clear of: the system bars, and the display cutout at the sides
 * unless the page is fullscreen (a fullscreen video may use the whole screen).
 */
@Composable
internal fun chromeWindowInsets(avoidCutout: Boolean): WindowInsets =
    if (avoidCutout) WindowInsets.systemBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
    else WindowInsets.systemBars

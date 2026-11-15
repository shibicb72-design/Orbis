package com.shibi.orbis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Material's [TopAppBar] animates its own container colour, so during a theme glide (ThemeFade hands it a new colour
 * every frame) it trails the rest of the screen. This bar gives Material a transparent container and paints
 * [containerColor] with a plain background instead, which follows the current colour scheme on every frame. The
 * background sits outside the bar's inset padding, so it also covers the status bar area.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbisTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    TopAppBar(
        title = title,
        modifier = modifier.background(containerColor),
        navigationIcon = navigationIcon,
        actions = actions,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                navigationIconContentColor = contentColor,
                titleContentColor = contentColor,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}

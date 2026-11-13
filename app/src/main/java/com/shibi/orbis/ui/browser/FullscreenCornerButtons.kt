package com.shibi.orbis.ui.browser

import android.view.RoundedCorner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R

internal const val FULLSCREEN_CAST_TAG = "fullscreen_cast"
internal const val FULLSCREEN_PIP_TAG = "fullscreen_pip"
internal val FULLSCREEN_CORNER_VISUAL = 24.dp
internal val FULLSCREEN_CORNER_ICON = 16.dp
internal val FULLSCREEN_CORNER_TOUCH = 32.dp
internal val FULLSCREEN_CORNER_MARGIN = 4.dp

/** How far in from the top and end edges the visuals sit: the margin, or more on a rounded screen corner. */
internal fun cornerInsetDp(cornerRadiusDp: Float): Float = maxOf(4f, 0.3f * cornerRadiusDp)

/**
 * Small Cast button, and below it a picture-in-picture button, for the top-right corner of a fullscreen page.
 * The visual is a small circle; the click lands on the larger square around it.
 */
@Composable
internal fun FullscreenCornerButtons(
    casting: Boolean,
    showPip: Boolean,
    onCast: () -> Unit,
    onPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val radiusPx = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0
    val inset = cornerInsetDp(with(density) { radiusPx.toDp().value }).dp
    // The visual sits inside its touch square by up to the slack; any further inset (a rounded corner) moves the square.
    val slack = FULLSCREEN_CORNER_TOUCH - FULLSCREEN_CORNER_VISUAL
    val inner = minOf(inset, slack)
    val outer = inset - inner
    Column(modifier.padding(top = outer, end = outer)) {
        CornerButton(
            icon = if (casting) Icons.Filled.CastConnected else Icons.Filled.Cast,
            description = stringResource(R.string.menu_cast),
            tag = FULLSCREEN_CAST_TAG,
            tinted = casting,
            top = inner,
            end = inner,
            onClick = onCast,
        )
        if (showPip) {
            CornerButton(
                icon = Icons.Filled.PictureInPictureAlt,
                description = stringResource(R.string.fullscreen_pip),
                tag = FULLSCREEN_PIP_TAG,
                tinted = false,
                top = minOf(FULLSCREEN_CORNER_MARGIN, slack),
                end = inner,
                onClick = onPip,
            )
        }
    }
}

@Composable
private fun CornerButton(
    icon: ImageVector,
    description: String,
    tag: String,
    tinted: Boolean,
    top: androidx.compose.ui.unit.Dp,
    end: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(FULLSCREEN_CORNER_TOUCH)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = top, end = end)
                .size(FULLSCREEN_CORNER_VISUAL)
                .testTag(tag),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = description,
                    tint = if (tinted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(FULLSCREEN_CORNER_ICON),
                )
            }
        }
    }
}

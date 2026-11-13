package com.shibi.orbis.ui.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.shibi.orbis.ui.theme.PROGRESS_FADE_MS
import com.shibi.orbis.ui.theme.motionTween
import com.shibi.orbis.ui.theme.rememberSmoothProgress

/** Counts loads: goes up each time [isLoading] turns true. */
private class LoadCounter(var wasLoading: Boolean, var number: Int)

/** The page-load bar. It always occupies [modifier]'s size (the caller fixes the 4 dp height) and never changes layout. */
@Composable
internal fun LoadingBar(
    isLoading: Boolean,
    progress: Float,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
) {
    val counter = remember { LoadCounter(wasLoading = false, number = 0) }
    if (isLoading && !counter.wasLoading) counter.number++
    counter.wasLoading = isLoading
    // After the load the bar goes to 100 % and fades; a new load has its own number, so it snaps to its own value.
    val smooth = rememberSmoothProgress(if (isLoading) progress else 1f, motionEnabled, resetKey = counter.number)
    val fade = remember { Animatable(if (isLoading) 1f else 0f) }
    LaunchedEffect(isLoading, motionEnabled) {
        when {
            isLoading -> fade.snapTo(1f)
            motionEnabled -> fade.animateTo(0f, motionTween(true, PROGRESS_FADE_MS))
            else -> fade.snapTo(0f)
        }
    }
    // Only a change of this flag recomposes; the fade itself runs in the layer block.
    val fading by remember { derivedStateOf { fade.value > 0f } }
    Box(modifier) {
        if (isLoading || (motionEnabled && fading)) {
            LinearProgressIndicator(
                progress = { smooth.value },
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (isLoading) 1f else fade.value },
                color = color,
            )
        }
    }
}

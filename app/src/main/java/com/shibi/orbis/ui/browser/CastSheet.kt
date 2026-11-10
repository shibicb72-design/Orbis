package com.shibi.orbis.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.cast.CastCoordinator
import com.shibi.orbis.cast.CastDeviceKind
import com.shibi.orbis.cast.CastNotice
import com.shibi.orbis.cast.CastPhase
import com.shibi.orbis.engine.BrowserSession

/** Cast icon kept on top of a fullscreen video. Its surface carries its own content colour. */
@Composable
internal fun CastFullscreenButton(casting: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        modifier = modifier,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                if (casting) Icons.Filled.CastConnected else Icons.Filled.Cast,
                contentDescription = stringResource(R.string.menu_cast),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CastSheet(
    coordinator: CastCoordinator,
    pageUrl: String,
    isPrivate: Boolean,
    tabId: Long,
    session: BrowserSession?,
    onDismiss: () -> Unit,
) {
    val phase by coordinator.phase.collectAsStateWithLifecycle()
    val devices by coordinator.devices.collectAsStateWithLifecycle()
    val playback by coordinator.playback.collectAsStateWithLifecycle()
    val notice by coordinator.notice.collectAsStateWithLifecycle()
    val dlnaFormatNote by coordinator.dlnaFormatNote.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationPermissionRequest()
    LaunchedEffect(session, pageUrl, tabId) {
        if (coordinator.playback.value != null) {
            coordinator.present(pageUrl, isPrivate, null, tabId)
        } else {
            val current = session
            if (current == null) {
                coordinator.present(pageUrl, isPrivate, null, tabId)
            } else {
                current.probeCastMedia { json -> coordinator.present(pageUrl, isPrivate, json, tabId) }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { coordinator.sheetClosed() }
    }
    BackHandler { onDismiss() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.cast_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            val youtube = (phase as? CastPhase.Picker)?.youtube == true || playback?.youtube == true
            if (youtube) {
                Text(stringResource(R.string.cast_youtube_note), style = MaterialTheme.typography.bodyMedium)
            }
            if (dlnaFormatNote) {
                Text(stringResource(R.string.cast_dlna_adaptive), style = MaterialTheme.typography.bodyMedium)
            }
            notice?.let { item ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(noticeLabel(item), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            when (val current = phase) {
                CastPhase.Idle, CastPhase.NoMedia -> Unit
                is CastPhase.Picker -> {
                    if (current.searching) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.cast_searching), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    devices.forEach { device ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    askNotifications()
                                    coordinator.choose(device.id) {
                                        session?.mediaPause()
                                        session?.pausePageMedia()
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(
                                        if (device.kind == CastDeviceKind.GOOGLE_CAST) R.string.cast_device_google else R.string.cast_device_dlna,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Filled.Cast, contentDescription = null)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                CastPhase.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.cast_connecting), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                CastPhase.Controls -> {
                    val playing = playback
                    if (playing != null) CastControls(playing, coordinator)
                    TextButton(
                        onClick = {
                            val currentSession = session
                            if (currentSession == null) {
                                coordinator.castThisPage(pageUrl, isPrivate, null, tabId)
                            } else {
                                currentSession.probeCastMedia { json -> coordinator.castThisPage(pageUrl, isPrivate, json, tabId) }
                            }
                        },
                    ) { Text(stringResource(R.string.cast_this_page)) }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.cast_close))
            }
        }
    }
}

@Composable
private fun CastControls(playback: com.shibi.orbis.cast.CastPlayback, coordinator: CastCoordinator) {
    Text(
        playback.title.ifBlank { stringResource(R.string.cast_notification_casting) },
        style = MaterialTheme.typography.titleMedium,
    )
    Text(playback.deviceName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (playback.playing) coordinator.pause() else coordinator.play() }) {
            Icon(
                if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (playback.playing) R.string.cast_pause else R.string.cast_play),
            )
        }
        OutlinedButton(onClick = coordinator::stopPlayback) { Text(stringResource(R.string.stop)) }
        OutlinedButton(onClick = coordinator::disconnect, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.cast_disconnect))
        }
    }
    if (playback.durationSec > 0.0) {
        var dragging by remember { mutableFloatStateOf(-1f) }
        val shown = if (dragging >= 0f) dragging else playback.positionSec.toFloat()
        Text(
            "${clock(shown.toDouble())} / ${clock(playback.durationSec)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = shown.coerceIn(0f, playback.durationSec.toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                val target = dragging
                dragging = -1f
                if (target >= 0f) coordinator.seekTo(target.toDouble())
            },
            valueRange = 0f..playback.durationSec.toFloat().coerceAtLeast(1f),
        )
    }
    Text(stringResource(R.string.cast_volume), style = MaterialTheme.typography.bodyMedium)
    var volumeDrag by remember { mutableFloatStateOf(-1f) }
    val volume = if (volumeDrag >= 0f) volumeDrag else playback.volume.coerceIn(0f, 1f)
    Slider(
        value = volume,
        onValueChange = { volumeDrag = it },
        onValueChangeFinished = {
            val target = volumeDrag
            volumeDrag = -1f
            if (target >= 0f) coordinator.setVolume(target)
        },
    )
}

@Composable
private fun noticeLabel(notice: CastNotice): String =
    stringResource(
        when (notice) {
            CastNotice.NO_MEDIA -> R.string.cast_no_media
            CastNotice.NO_DEVICES -> R.string.cast_no_devices
            CastNotice.YOUTUBE_NEEDS_GOOGLE -> R.string.cast_youtube_need_google
            CastNotice.FAILED -> R.string.cast_failed
            CastNotice.GOOGLE_UNAVAILABLE -> R.string.cast_google_unavailable
        },
    )

private fun clock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

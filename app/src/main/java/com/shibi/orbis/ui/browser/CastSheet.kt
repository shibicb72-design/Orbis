package com.shibi.orbis.ui.browser

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import com.shibi.orbis.cast.CastTrustPrompt
import com.shibi.orbis.cast.CastTrustReason
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.ui.ObscuredTouchFilter
import com.shibi.orbis.ui.rememberTapGuard

internal const val CAST_BUTTON_TAG = "toolbar_cast"

/** Below this width a one-row toolbar has no room for the Cast icon; the menu's Cast item stays either way. */
internal const val TOOLBAR_CAST_MIN_WIDTH_DP = 360

internal fun showsToolbarCast(windowWidthDp: Int, twoRow: Boolean): Boolean =
    twoRow || windowWidthDp >= TOOLBAR_CAST_MIN_WIDTH_DP

/**
 * Connected: the theme's primary (teal; the lighter tonal teal on the dark toolbar so it stays readable).
 * Otherwise the content colour the IconButton provides, which is already its disabled colour when disabled.
 */
@Composable
private fun castTint(connected: Boolean): Color =
    if (connected) MaterialTheme.colorScheme.primary else LocalContentColor.current

/** Cast icon in the top toolbar, between the tab count and the menu. */
@Composable
internal fun ToolbarCastButton(
    connected: Boolean,
    enabled: Boolean,
    deviceName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description =
        if (connected) stringResource(R.string.cast_toolbar_connected, deviceName.orEmpty()) else stringResource(R.string.cast_toolbar_idle)
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.testTag(CAST_BUTTON_TAG)) {
        Icon(
            if (connected) Icons.Filled.CastConnected else Icons.Filled.Cast,
            contentDescription = description,
            tint = castTint(connected && enabled),
        )
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
    val trustPrompt by coordinator.trustPrompt.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationPermissionRequest()
    val askNearby = rememberCastNearbyPermission { coordinator.rediscover() }
    LaunchedEffect(session, pageUrl, tabId) {
        askNearby()
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
                val text = castNoticeText(item)
                if (text != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                    }
                } else {
                    trustPrompt?.let { prompt ->
                        CastTrustQuestion(prompt, onConfirm = coordinator::confirmTrust, onCancel = coordinator::dismissNotice)
                    }
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
                                        if (device.kind == CastDeviceKind.GOOGLE_CAST) R.string.cast_device_chromecast else R.string.cast_device_dlna,
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

internal const val CAST_TRUST_CONFIRM_TAG = "cast_trust_confirm"
internal const val CAST_TRUST_CANCEL_TAG = "cast_trust_cancel"
internal const val CAST_TRUST_TEXT_TAG = "cast_trust_text"

/**
 * The question for [CastNotice.CONFIRM_DEVICE], drawn on the sheet's own container. A changed key is a warning: its
 * text takes the scheme's error colour (paired with the surface the sheet sits on) and "Cancel" is the filled button.
 * A first cast or a rename is neutral: content-colour text and "Trust and cast" filled.
 */
@Composable
internal fun CastTrustQuestion(
    prompt: CastTrustPrompt,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val warning = prompt.reason == CastTrustReason.KEY_CHANGED
    // Drawn inside the Cast sheet, so both belong to the sheet's own window. Cancel is never delayed.
    ObscuredTouchFilter()
    val tapGuard = rememberTapGuard(prompt)
    val confirm = { if (tapGuard()) onConfirm() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(castTrustText(prompt.reason), prompt.deviceName),
            style = MaterialTheme.typography.bodyMedium,
            color = if (warning) MaterialTheme.colorScheme.error else LocalContentColor.current,
            modifier = Modifier.testTag(CAST_TRUST_TEXT_TAG),
        )
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (warning) {
                TextButton(onClick = confirm, modifier = Modifier.testTag(CAST_TRUST_CONFIRM_TAG)) {
                    Text(stringResource(R.string.cast_trust_confirm))
                }
                Button(onClick = onCancel, modifier = Modifier.testTag(CAST_TRUST_CANCEL_TAG)) {
                    Text(stringResource(R.string.cast_trust_cancel))
                }
            } else {
                TextButton(onClick = onCancel, modifier = Modifier.testTag(CAST_TRUST_CANCEL_TAG)) {
                    Text(stringResource(R.string.cast_trust_cancel))
                }
                Button(onClick = confirm, modifier = Modifier.testTag(CAST_TRUST_CONFIRM_TAG)) {
                    Text(stringResource(R.string.cast_trust_confirm))
                }
            }
        }
    }
}

@StringRes
internal fun castTrustText(reason: CastTrustReason): Int =
    when (reason) {
        CastTrustReason.FIRST_USE -> R.string.cast_trust_first
        CastTrustReason.KEY_CHANGED -> R.string.cast_trust_changed
        CastTrustReason.RENAMED -> R.string.cast_trust_renamed
    }

/** The notice text, or null for [CastNotice.CONFIRM_DEVICE], which is drawn by [CastTrustQuestion]. */
@StringRes
internal fun castNoticeText(notice: CastNotice): Int? =
    when (notice) {
        CastNotice.NO_MEDIA -> R.string.cast_no_media
        CastNotice.NO_DEVICES -> R.string.cast_no_devices
        CastNotice.YOUTUBE_NEEDS_GOOGLE -> R.string.cast_youtube_need_google
        CastNotice.FAILED -> R.string.cast_failed
        CastNotice.YOUTUBE_UNCONFIRMED -> R.string.cast_youtube_unconfirmed
        CastNotice.REJECTED -> R.string.cast_rejected
        CastNotice.CONFIRM_DEVICE -> null
    }

private fun clock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

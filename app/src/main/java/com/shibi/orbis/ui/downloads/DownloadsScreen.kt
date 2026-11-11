package com.shibi.orbis.ui.downloads

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.ui.secondaryReadingWidth
import com.shibi.orbis.R
import com.shibi.orbis.downloads.DownloadEntry
import com.shibi.orbis.downloads.DownloadIntents
import com.shibi.orbis.downloads.DownloadManager
import com.shibi.orbis.downloads.DownloadStatus
import com.shibi.orbis.torrent.TorrentEntry
import com.shibi.orbis.torrent.TorrentErrorKind
import com.shibi.orbis.torrent.TorrentManager
import com.shibi.orbis.torrent.TorrentStatus
import com.shibi.orbis.ui.OrbisEmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** One row of the merged list: a file download or a torrent. Keys never collide ("d:" and "t:" prefixes). */
private sealed interface ListRow {
    val key: String
    val time: Long

    class File(val entry: DownloadEntry) : ListRow {
        override val key get() = "d:" + entry.id
        override val time get() = entry.startedAt
    }

    class Torrent(val entry: TorrentEntry, val model: TorrentRowModel) : ListRow {
        override val key get() = "t:" + entry.id
        override val time get() = entry.addedAt
    }
}

/**
 * File downloads and torrents in one list, newest first. [torrents] is null where there are none (screen tests,
 * a Gecko child process). [log] gets status lines only (see TorrentLog).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    manager: DownloadManager,
    torrents: TorrentManager?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    log: (String) -> Unit = {},
) {
    val entries by manager.entries.collectAsStateWithLifecycle()
    val noTorrents = remember { MutableStateFlow<List<TorrentEntry>>(emptyList()) }
    val torrentEntries by (torrents?.entries ?: noTorrents).collectAsStateWithLifecycle()
    val rows =
        remember(entries, torrentEntries) {
            val files = entries.map { ListRow.File(it) }
            val tors = torrentEntries.mapNotNull { e -> TorrentRowModel.from(e)?.let { ListRow.Torrent(e, it) } }
            (files + tors).sortedByDescending { it.time }
        }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<DownloadEntry?>(null) }
    var pendingRemove by remember { mutableStateOf<TorrentEntry?>(null) }

    val chooserOpen = stringResource(R.string.downloads_open_chooser)
    val chooserShare = stringResource(R.string.downloads_share_chooser)
    val msgMissing = stringResource(R.string.downloads_file_missing)
    val msgNoApp = stringResource(R.string.downloads_no_app)
    val msgFailed = stringResource(R.string.downloads_action_failed)
    val msgSavedPrivate = stringResource(R.string.torrents_state_saved_private)

    fun report(result: DownloadIntents.Result) {
        val message =
            when (result) {
                DownloadIntents.Result.OK -> return
                DownloadIntents.Result.MISSING -> msgMissing
                DownloadIntents.Result.NO_APP -> msgNoApp
                DownloadIntents.Result.FAILED -> msgFailed
            }
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.downloads_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            OrbisEmptyState(stringResource(R.string.downloads_empty), Icons.Filled.Download, Modifier.padding(padding))
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().secondaryReadingWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    if (row is ListRow.Torrent) {
                        val t = torrents ?: return@items
                        val entry = row.entry
                        val savedIn = if (entry.isPrivate) msgSavedPrivate else stringResource(R.string.torrents_state_saved, entry.displayName)
                        TorrentRow(
                            model = row.model,
                            onPause = { t.pause(entry.id) },
                            onResume = { t.resume(entry.id) },
                            onRetry = { t.retry(entry.id) },
                            onOpen = {
                                scope.launch {
                                    val result =
                                        DownloadIntents.openPublished(context, entry, chooserOpen, log) {
                                            scope.launch { snackbar.showSnackbar(savedIn) }
                                        }
                                    report(result)
                                }
                            },
                            onRemove = { pendingRemove = entry },
                        )
                        return@items
                    }
                    val entry = (row as ListRow.File).entry
                    DownloadRow(
                        entry = entry,
                        onCancel = { manager.cancel(entry.id) },
                        onPause = if (manager.canPause) ({ manager.pause(entry.id) }) else null,
                        onResume = { manager.resume(entry.id) },
                        onOpen = { report(DownloadIntents.open(context, entry, chooserOpen)) },
                        onShare = { report(DownloadIntents.share(context, entry, chooserShare)) },
                        onDelete = {
                            // A file on disk deserves a confirmation; a failed or cancelled row has nothing to lose.
                            if (entry.status == DownloadStatus.COMPLETED) pendingDelete = entry else manager.delete(entry.id)
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        val onSurface = MaterialTheme.colorScheme.onSurface
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = onSurface,
            textContentColor = onSurface,
            title = { Text(stringResource(R.string.downloads_delete_confirm_title)) },
            text = { Text(stringResource(R.string.downloads_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager.delete(entry.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = onSurface),
                ) { Text(stringResource(R.string.downloads_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = onSurface),
                ) { Text(stringResource(R.string.downloads_cancel)) }
            },
        )
    }

    pendingRemove?.let { entry ->
        RemoveTorrentDialog(
            entry = entry,
            onRemove = { deleteFiles ->
                torrents?.remove(entry.id, deleteFiles)
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null },
        )
    }
}

/**
 * An unfinished torrent has no usable files outside the app (they are published only when it finishes), so removing
 * it deletes what was downloaded. A completed one only leaves the list: its files stay in Download/Orbis/<name>.
 */
@Composable
private fun RemoveTorrentDialog(entry: TorrentEntry, onRemove: (deleteFiles: Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val completed = entry.status == TorrentStatus.COMPLETED
    val title = stringResource(if (completed) R.string.torrents_remove_done_title else R.string.torrents_remove_title)
    val body =
        when {
            completed && entry.isPrivate -> stringResource(R.string.torrents_remove_done_body_private)
            completed -> stringResource(R.string.torrents_remove_done_body, entry.displayName)
            entry.doneBytes <= 0 -> stringResource(R.string.torrents_remove_body_nothing)
            else -> stringResource(R.string.torrents_remove_body, Formatter.formatShortFileSize(context, entry.doneBytes))
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = onSurface,
        textContentColor = onSurface,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = { onRemove(!completed) },
                colors = ButtonDefaults.textButtonColors(contentColor = onSurface),
            ) { Text(stringResource(R.string.torrents_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = onSurface)) {
                Text(stringResource(R.string.torrents_cancel))
            }
        },
    )
}

@Composable
private fun TorrentRow(
    model: TorrentRowModel,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // Same neutral actions as a file row: the name and progress carry the hierarchy.
    val textButton = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)
    val iconButton = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurface)
    val secondary = colors.onSurface.copy(alpha = 0.8f)
    val failed = model.state is RowState.Failed

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                model.title ?: stringResource(R.string.torrents_private_title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stateText(model),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) colors.error else secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val percent = model.percent
            if (model.state != RowState.Completed && !failed) {
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.primary,
                        trackColor = colors.onSurface.copy(alpha = 0.2f),
                    )
                } else if (model.indeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.primary,
                        trackColor = colors.onSurface.copy(alpha = 0.2f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statsText(context, model),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    modifier = Modifier.weight(1f),
                )
                if (RowAction.PAUSE in model.actions) {
                    TextButton(onClick = onPause, colors = textButton) { Text(stringResource(R.string.torrents_pause)) }
                }
                if (RowAction.RESUME in model.actions) {
                    TextButton(onClick = onResume, colors = textButton) { Text(stringResource(R.string.torrents_resume)) }
                }
                if (RowAction.RETRY in model.actions) {
                    TextButton(onClick = onRetry, colors = textButton) { Text(stringResource(R.string.torrents_retry)) }
                }
                if (RowAction.OPEN in model.actions) {
                    IconButton(onClick = onOpen, colors = iconButton) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.downloads_open))
                    }
                }
                if (RowAction.REMOVE in model.actions) {
                    IconButton(onClick = onRemove, colors = iconButton) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.torrents_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun stateText(model: TorrentRowModel): String =
    when (val s = model.state) {
        RowState.Fetching -> stringResource(R.string.torrents_state_fetching)
        RowState.Downloading -> stringResource(R.string.torrents_state_downloading)
        RowState.Checking -> stringResource(R.string.torrents_state_checking)
        RowState.Seeding -> stringResource(R.string.torrents_state_seeding)
        RowState.PausedUser -> stringResource(R.string.torrents_state_paused)
        RowState.PausedQueued -> stringResource(R.string.torrents_state_queued)
        RowState.PausedTimeout -> stringResource(R.string.torrents_state_paused_timeout)
        RowState.WaitingWifi -> stringResource(R.string.torrents_state_waiting_wifi)
        RowState.Publishing -> stringResource(R.string.torrents_state_publishing)
        RowState.Completed ->
            model.title?.let { stringResource(R.string.torrents_state_saved, it) } ?: stringResource(R.string.torrents_state_saved_private)
        is RowState.Failed ->
            stringResource(
                when (s.kind) {
                    TorrentErrorKind.NO_SPACE -> R.string.torrents_failed_no_space
                    TorrentErrorKind.FILE_ERROR -> R.string.torrents_failed_file
                    TorrentErrorKind.INVALID_SOURCE, TorrentErrorKind.ENGINE_ERROR -> R.string.torrents_failed_load
                    TorrentErrorKind.UNSAFE_METADATA -> R.string.torrents_failed_unsafe
                    TorrentErrorKind.MISSING_DATA -> R.string.torrents_failed_missing
                    TorrentErrorKind.PUBLISH_FAILED -> R.string.torrents_failed_publish
                },
            )
    }

/** "<done> of <total> · <speed>/s · <n> peers · <eta> left", each part left out when unknown. */
private fun statsText(context: android.content.Context, model: TorrentRowModel): String {
    val parts = ArrayList<String>(4)
    val done = Formatter.formatShortFileSize(context, model.doneBytes)
    when {
        model.totalBytes > 0 && model.state == RowState.Completed -> parts += Formatter.formatShortFileSize(context, model.totalBytes)
        model.totalBytes > 0 -> parts += context.getString(R.string.downloads_progress_known, done, Formatter.formatShortFileSize(context, model.totalBytes))
        model.doneBytes > 0 -> parts += context.getString(R.string.downloads_progress_unknown, done)
    }
    if (model.speedBps > 0) parts += context.getString(R.string.torrents_stats_speed, Formatter.formatShortFileSize(context, model.speedBps))
    model.peers?.let { parts += context.resources.getQuantityString(R.plurals.torrents_stats_peers, it, it) }
    model.etaSeconds?.let { parts += context.getString(R.string.torrents_stats_eta, DateUtils.formatElapsedTime(it)) }
    return parts.joinToString(" · ")
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    onCancel: () -> Unit,
    onPause: (() -> Unit)?,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // Keep row actions neutral so the file name and progress carry the hierarchy.
    val textButton = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)
    val iconButton = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurface)
    val secondary = colors.onSurface.copy(alpha = 0.8f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.sourceHost?.let { host ->
                Text(
                    stringResource(R.string.downloads_from_host, host),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (entry.status) {
                DownloadStatus.IN_PROGRESS -> {
                    if (entry.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (entry.receivedBytes.toFloat() / entry.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.primary,
                            trackColor = colors.onSurface.copy(alpha = 0.2f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.primary,
                            trackColor = colors.onSurface.copy(alpha = 0.2f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            progressText(context, entry),
                            style = MaterialTheme.typography.bodySmall,
                            color = secondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (onPause != null) {
                            TextButton(onClick = onPause, colors = textButton) {
                                Text(stringResource(R.string.downloads_pause))
                            }
                        }
                        TextButton(onClick = onCancel, colors = textButton) {
                            Text(stringResource(R.string.downloads_cancel))
                        }
                    }
                }
                DownloadStatus.PAUSED -> {
                    if (entry.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (entry.receivedBytes.toFloat() / entry.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.primary,
                            trackColor = colors.onSurface.copy(alpha = 0.2f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.downloads_status_paused) + " - " + progressText(context, entry),
                            style = MaterialTheme.typography.bodySmall,
                            color = secondary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onResume, colors = textButton) {
                            Text(stringResource(R.string.downloads_resume))
                        }
                        TextButton(onClick = onCancel, colors = textButton) {
                            Text(stringResource(R.string.downloads_cancel))
                        }
                    }
                }
                DownloadStatus.COMPLETED -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Formatter.formatShortFileSize(context, entry.receivedBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = secondary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onOpen, colors = iconButton) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.downloads_open),
                            )
                        }
                        IconButton(onClick = onShare, colors = iconButton) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.downloads_share))
                        }
                        IconButton(onClick = onDelete, colors = iconButton) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.downloads_delete))
                        }
                    }
                }
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                if (entry.status == DownloadStatus.FAILED) {
                                    R.string.downloads_status_failed
                                } else {
                                    R.string.downloads_status_cancelled
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (entry.status == DownloadStatus.FAILED) colors.error else secondary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDelete, colors = iconButton) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.downloads_delete))
                        }
                    }
                }
            }
        }
    }
}

private fun progressText(context: android.content.Context, entry: DownloadEntry): String {
    val received = Formatter.formatShortFileSize(context, entry.receivedBytes)
    return if (entry.totalBytes > 0) {
        context.getString(
            R.string.downloads_progress_known,
            received,
            Formatter.formatShortFileSize(context, entry.totalBytes),
        )
    } else {
        context.getString(R.string.downloads_progress_unknown, received)
    }
}

package com.shibi.orbis.ui.downloads

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
import com.shibi.orbis.ui.OrbisEmptyState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(manager: DownloadManager, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val entries by manager.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<DownloadEntry?>(null) }

    val chooserOpen = stringResource(R.string.downloads_open_chooser)
    val chooserShare = stringResource(R.string.downloads_share_chooser)
    val msgMissing = stringResource(R.string.downloads_file_missing)
    val msgNoApp = stringResource(R.string.downloads_no_app)
    val msgFailed = stringResource(R.string.downloads_action_failed)

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
        if (entries.isEmpty()) {
            OrbisEmptyState(stringResource(R.string.downloads_empty), Icons.Filled.Download, Modifier.padding(padding))
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().secondaryReadingWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    DownloadRow(
                        entry = entry,
                        onCancel = { manager.cancel(entry.id) },
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
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    onCancel: () -> Unit,
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

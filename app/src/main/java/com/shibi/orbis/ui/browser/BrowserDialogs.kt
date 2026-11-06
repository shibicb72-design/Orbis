package com.shibi.orbis.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.downloads.DownloadFileNames
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.tabs.LinkMenuRequest
import com.shibi.orbis.tabs.PendingDownload

/**
 * Asks before anything is written to disk, so a page cannot fill the device by triggering downloads. The name shown is
 * the same sanitised name the download will be saved under, never the raw header value.
 */
@Composable
fun DownloadConfirmDialog(pending: PendingDownload, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val download = pending.download
    val name =
        remember(pending) {
            runCatching {
                DownloadFileNames.resolve(
                    download.contentDisposition,
                    download.url,
                    download.contentType,
                ) { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            }.getOrDefault(DownloadFileNames.FALLBACK_NAME)
        }
    val host = remember(pending) { hostOfUrl(download.url) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.browser_download_title)) },
        text = {
            Column {
                Text(name, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    if (download.contentLength >= 0) {
                        stringResource(R.string.browser_download_size, Formatter.formatFileSize(context, download.contentLength))
                    } else {
                        stringResource(R.string.browser_download_size_unknown)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (host != null) {
                    Text(stringResource(R.string.browser_download_from, host), modifier = Modifier.padding(top = 4.dp))
                }
                if (pending.isPrivate) {
                    Text(
                        stringResource(R.string.browser_download_private_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.browser_download_start)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.prompt_cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
    )
}

/** Long-press on a link: open in a new tab, copy, share. */
@Composable
fun LinkMenuDialog(request: LinkMenuRequest, onOpenInNewTab: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.url, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenInNewTab) { Text(stringResource(R.string.link_menu_open_new_tab)) }
                TextButton(
                    onClick = {
                        copyLink(context, request.url)
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.link_menu_copy)) }
                TextButton(
                    onClick = {
                        shareLink(context, request.url)
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.link_menu_share)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
    )
}

private fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("link", url))
    // Android 13+ shows its own "copied" confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.link_menu_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun shareLink(context: Context, url: String) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.link_menu_share))) }
}

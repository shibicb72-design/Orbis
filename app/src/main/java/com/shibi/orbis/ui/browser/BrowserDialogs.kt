package com.shibi.orbis.ui.browser

import android.content.ClipData
import androidx.compose.foundation.clickable
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.downloads.DownloadFileNames
import com.shibi.orbis.engine.ContextMenuItem
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.engine.isImageAction
import com.shibi.orbis.tabs.LinkMenuRequest
import com.shibi.orbis.tabs.PendingDownload
import com.shibi.orbis.tabs.pageMenu

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

/**
 * Long-press on a link and/or an image. Http(s) links open in a tab (including a private one), copy, share or
 * download. Other schemes (magnet:) copy, share, or open in another app, and are not loaded as a page. An image that
 * is also a link shows both groups. The header is the address, shortened when it is long.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkMenuSheet(
    request: LinkMenuRequest,
    onOpenInNewTab: () -> Unit,
    onOpenInPrivateTab: () -> Unit,
    onDownloadLink: () -> Unit,
    onOpenExternal: () -> Unit,
    onDismiss: () -> Unit,
    onOpenImageInNewTab: () -> Unit = {},
    onSaveImage: () -> Unit = {},
    /** Null hides "Download with Orbis" (Settings > Magnet links is "Open in another app"). */
    onDownloadTorrent: (() -> Unit)? = null,
) {
    val menu = request.pageMenu()?.let { m ->
        if (onDownloadTorrent != null) m else m.copy(items = m.items - ContextMenuItem.DOWNLOAD_TORRENT)
    }
    if (menu == null) {
        LaunchedEffect(request) { onDismiss() }
        return
    }
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = onSurface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                menu.header,
                style = MaterialTheme.typography.titleMedium,
                color = onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
            )
            val firstImage = menu.items.indexOfFirst { it.isImageAction }
            menu.items.forEachIndexed { index, item ->
                if (index == firstImage && firstImage > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                TextButton(
                    onClick = {
                        when (item) {
                            ContextMenuItem.OPEN_NEW_TAB -> onOpenInNewTab()
                            ContextMenuItem.OPEN_NEW_PRIVATE_TAB -> onOpenInPrivateTab()
                            ContextMenuItem.COPY_LINK_ADDRESS -> {
                                copyLink(context, request.url)
                                onDismiss()
                            }
                            ContextMenuItem.COPY_LINK_TEXT -> {
                                val text = menu.linkText
                                if (text != null) copyLink(context, text)
                                onDismiss()
                            }
                            ContextMenuItem.SHARE_LINK -> {
                                shareLink(context, request.url)
                                onDismiss()
                            }
                            ContextMenuItem.DOWNLOAD_LINK -> onDownloadLink()
                            ContextMenuItem.DOWNLOAD_TORRENT -> onDownloadTorrent?.invoke()
                            ContextMenuItem.OPEN_EXTERNAL -> onOpenExternal()
                            ContextMenuItem.OPEN_IMAGE_NEW_TAB -> onOpenImageInNewTab()
                            ContextMenuItem.SAVE_IMAGE -> onSaveImage()
                            ContextMenuItem.COPY_IMAGE_ADDRESS -> {
                                val image = request.imageUrl
                                if (image != null) copyLink(context, image)
                                onDismiss()
                            }
                            ContextMenuItem.SHARE_IMAGE -> {
                                val image = request.imageUrl
                                if (image != null) shareLink(context, image)
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = onSurface),
                ) {
                    Text(
                        contextMenuLabel(item),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyLarge,
                        color = onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun contextMenuLabel(item: ContextMenuItem): String =
    stringResource(
        when (item) {
            ContextMenuItem.OPEN_NEW_TAB -> R.string.link_menu_open_new_tab
            ContextMenuItem.OPEN_NEW_PRIVATE_TAB -> R.string.link_menu_open_private
            ContextMenuItem.COPY_LINK_ADDRESS -> R.string.link_menu_copy
            ContextMenuItem.COPY_LINK_TEXT -> R.string.link_menu_copy_text
            ContextMenuItem.SHARE_LINK -> R.string.link_menu_share
            ContextMenuItem.DOWNLOAD_LINK -> R.string.link_menu_download
            ContextMenuItem.DOWNLOAD_TORRENT -> R.string.torrents_menu_download
            ContextMenuItem.OPEN_EXTERNAL -> R.string.link_menu_open_external
            ContextMenuItem.OPEN_IMAGE_NEW_TAB -> R.string.image_menu_open_new_tab
            ContextMenuItem.SAVE_IMAGE -> R.string.image_menu_save
            ContextMenuItem.COPY_IMAGE_ADDRESS -> R.string.image_menu_copy
            ContextMenuItem.SHARE_IMAGE -> R.string.image_menu_share
        },
    )

internal fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("link", url))
    // Android 13+ shows its own "copied" confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.link_menu_copied, Toast.LENGTH_SHORT).show()
    }
}

internal fun shareLink(context: Context, url: String, title: String? = null) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
            .apply { if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title) }
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.link_menu_share))) }
}

/** The recently closed normal tabs, newest first; tapping one reopens it with its history. */
@Composable
internal fun RecentlyClosedDialog(
    entries: List<com.shibi.orbis.tabs.ClosedTab>,
    onOpen: (com.shibi.orbis.tabs.ClosedTab) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tabs_recently_closed)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                items(entries.size) { i ->
                    val e = entries[i]
                    val host = com.shibi.orbis.engine.hostOfUrl(e.url).orEmpty()
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text(e.title.ifBlank { host }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        supportingContent = { Text(host, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { onOpen(e) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) } },
        dismissButton = { TextButton(onClick = onClear) { Text(stringResource(R.string.tabs_recently_closed_clear)) } },
    )
}

/**
 * A strong password made on the phone. Copy puts it on the clipboard (marked sensitive) to paste into the sign-up
 * form; Orbis then offers to save it when the form is sent. It is not stored anywhere before that.
 */
@Composable
internal fun GeneratedPasswordDialog(password: String, onCopy: () -> Unit, onAnother: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.genpw_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        password,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    )
                }
                Text(
                    stringResource(R.string.genpw_note),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.genpw_copy)) } },
        dismissButton = { TextButton(onClick = onAnother) { Text(stringResource(R.string.genpw_another)) } },
    )
}

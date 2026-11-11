package com.shibi.orbis.ui.browser

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.tabs.PendingTorrentIntake
import com.shibi.orbis.torrent.IntakeLog
import com.shibi.orbis.torrent.IntakeRefusal
import com.shibi.orbis.torrent.IntakeRequest
import com.shibi.orbis.torrent.MagnetLink
import com.shibi.orbis.torrent.TorrentErrorKind
import com.shibi.orbis.torrent.TorrentIntakeReader
import com.shibi.orbis.torrent.TorrentPaths
import com.shibi.orbis.torrent.TorrentPreview
import com.shibi.orbis.torrent.TorrentSource
import com.shibi.orbis.ui.ObscuredTouchFilter
import com.shibi.orbis.ui.rememberTapGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a .torrent's bytes are: still coming, in hand (with what the preview reader made of them), or lost. */
sealed interface TorrentBody {
    object Reading : TorrentBody

    class Ready(val bytes: ByteArray, val preview: TorrentPreview.Preview) : TorrentBody {
        override fun toString(): String = "Ready"
    }

    class Failed(val reason: IntakeRefusal) : TorrentBody {
        override fun toString(): String = "Failed(${reason.name})"
    }
}

/** What the sheet shows when the torrent names nothing usable. */
enum class TorrentUnnamed { TORRENT_FILE, MAGNET_LINK }

/** What the confirm sheet shows. Pure: built by [of], tested without Compose. */
data class TorrentConfirmModel(
    /** Safe for display; null shows [unnamed]. */
    val nameText: String?,
    val unnamed: TorrentUnnamed,
    val sizeBytes: Long?,
    val fileCount: Int?,
    val isPrivate: Boolean,
    /** The full IP note (until one torrent was confirmed with it on screen); otherwise the one-line reminder. */
    val showFirstUseNote: Boolean,
    val reading: Boolean,
    val downloadEnabled: Boolean,
    val error: IntakeRefusal?,
) {
    // A torrent name must not end up in a log line.
    override fun toString(): String = "TorrentConfirmModel(enabled=$downloadEnabled, error=$error)"

    companion object {
        fun of(request: IntakeRequest, body: TorrentBody, ipNoteSeen: Boolean): TorrentConfirmModel {
            val base =
                TorrentConfirmModel(
                    nameText = null,
                    unnamed = TorrentUnnamed.TORRENT_FILE,
                    sizeBytes = null,
                    fileCount = null,
                    isPrivate = request.isPrivate,
                    showFirstUseNote = !ipNoteSeen,
                    reading = false,
                    downloadEnabled = false,
                    error = null,
                )
            if (request is IntakeRequest.Magnet) {
                val link = MagnetLink.parse(request.uri) ?: return base.copy(unnamed = TorrentUnnamed.MAGNET_LINK, error = IntakeRefusal.BAD_MAGNET)
                return base.copy(nameText = link.displayName, unnamed = TorrentUnnamed.MAGNET_LINK, downloadEnabled = true)
            }
            return when (body) {
                TorrentBody.Reading ->
                    base.copy(
                        // The response's own length until its bytes are in (the torrent's size comes with them).
                        sizeBytes = (request as? IntakeRequest.TorrentResponse)?.download?.contentLength?.takeIf { it >= 0 },
                        reading = true,
                    )
                is TorrentBody.Failed -> base.copy(error = body.reason)
                is TorrentBody.Ready ->
                    base.copy(
                        nameText = body.preview.name,
                        sizeBytes = body.preview.totalBytes,
                        fileCount = body.preview.fileCount,
                        downloadEnabled = true,
                    )
            }
        }
    }
}

/** The preview of bytes in hand. Off the main thread: a 4 MiB file is a lot to walk. */
suspend fun torrentBodyOf(bytes: ByteArray): TorrentBody.Ready =
    withContext(Dispatchers.Default) { TorrentBody.Ready(bytes, TorrentPreview.fromTorrent(bytes) ?: TorrentPreview.UNKNOWN) }

/**
 * The one way a torrent is added: the sheet reads what needs reading, shows [TorrentConfirmModel], and only its Download
 * button calls [onDownload] (with the checked source, the request's private flag, and whether the full IP note was on
 * screen). Cancel or dismiss ends a web response's body ([IncomingDownload.discard], once) and calls [onCancel].
 */
@Composable
fun TorrentIntakeSheet(
    pending: PendingTorrentIntake,
    ipNoteSeen: Boolean,
    reader: TorrentIntakeReader,
    readUri: suspend (String) -> TorrentIntakeReader.Result,
    onDownload: (source: TorrentSource, isPrivate: Boolean, noteShown: Boolean) -> Unit,
    onCancel: () -> Unit,
    log: (String) -> Unit,
) {
    val request = pending.request
    // A magnet has no body; everything else starts as "reading" until the effect below has the bytes.
    var body by remember(pending) { mutableStateOf<TorrentBody>(TorrentBody.Reading) }
    LaunchedEffect(pending) {
        val read =
            when (request) {
                is IntakeRequest.TorrentResponse -> reader.readResponse(request.download)
                is IntakeRequest.TorrentUri -> readUri(request.uri)
                is IntakeRequest.TorrentBytes -> TorrentIntakeReader.Result.Ok(request.bytes)
                is IntakeRequest.Magnet -> {
                    if (MagnetLink.parse(request.uri) == null) log(IntakeLog.refused(IntakeRefusal.BAD_MAGNET))
                    return@LaunchedEffect
                }
            }
        body =
            when (read) {
                is TorrentIntakeReader.Result.Ok -> torrentBodyOf(read.bytes)
                is TorrentIntakeReader.Result.Failed -> {
                    log(IntakeLog.refused(read.reason))
                    TorrentBody.Failed(read.reason)
                }
            }
    }
    val model = TorrentConfirmModel.of(request, body, ipNoteSeen)
    TorrentConfirmSheet(
        model = model,
        onDownload = download@{
            val source =
                when (request) {
                    is IntakeRequest.Magnet -> TorrentSource.Magnet(request.uri)
                    else -> TorrentSource.TorrentFile((body as? TorrentBody.Ready)?.bytes ?: return@download)
                }
            // Cheap and pure: the same check the manager makes, so nothing it would refuse is even handed over.
            val refused = TorrentPaths.checkSource(source)
            if (refused != null) {
                val reason = if (refused == TorrentErrorKind.UNSAFE_METADATA) IntakeRefusal.TOO_LARGE else IntakeRefusal.BAD_MAGNET
                log(IntakeLog.refused(reason))
                body = TorrentBody.Failed(reason)
                return@download
            }
            onDownload(source, request.isPrivate, model.showFirstUseNote)
        },
        onCancel = {
            (request as? IntakeRequest.TorrentResponse)?.download?.discard()
            log(IntakeLog.CANCELLED)
            onCancel()
        },
    )
}

/** The sheet itself (stateless). Every colour comes from the sheet's own container, in both themes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentConfirmSheet(model: TorrentConfirmModel, onDownload: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val container = MaterialTheme.colorScheme.surfaceContainerLow
    val onContainer = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = container,
        contentColor = onContainer,
    ) {
        // Composed inside the sheet: the filter and the guard belong to the sheet's own window.
        ObscuredTouchFilter()
        val tapGuard = rememberTapGuard()
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.torrents_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = onContainer,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                model.nameText
                    ?: stringResource(if (model.unnamed == TorrentUnnamed.MAGNET_LINK) R.string.torrents_sheet_unknown_magnet else R.string.torrents_sheet_unknown_file),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = onContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val parts =
                listOfNotNull(
                    model.sizeBytes?.let { Formatter.formatFileSize(context, it) },
                    model.fileCount?.let { pluralStringResource(R.plurals.torrents_sheet_files, it, it) },
                )
            if (parts.isNotEmpty()) {
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = secondary)
            }
            if (model.reading) {
                Text(stringResource(R.string.torrents_sheet_reading), style = MaterialTheme.typography.bodyMedium, color = secondary)
            }
            model.error?.let { error ->
                Text(
                    stringResource(
                        when (error) {
                            IntakeRefusal.TOO_LARGE -> R.string.torrents_sheet_too_large
                            IntakeRefusal.BAD_MAGNET -> R.string.torrents_sheet_bad_magnet
                            else -> R.string.torrents_sheet_read_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (model.isPrivate) {
                Text(stringResource(R.string.torrents_sheet_private), style = MaterialTheme.typography.bodyMedium, color = secondary)
            }
            if (model.showFirstUseNote) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.torrents_sheet_ip_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                Text(stringResource(R.string.torrents_sheet_ip_reminder), style = MaterialTheme.typography.bodySmall, color = secondary)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                    Text(stringResource(R.string.prompt_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if (tapGuard()) onDownload() }, enabled = model.downloadEnabled) {
                    Text(stringResource(R.string.torrents_sheet_download))
                }
            }
        }
    }
}

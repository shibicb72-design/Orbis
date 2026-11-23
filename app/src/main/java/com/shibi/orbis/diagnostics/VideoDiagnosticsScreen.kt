package com.shibi.orbis.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.ui.OrbisTopAppBar
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The name Save as text file suggests: `orbis-video-diagnostics-<date>.txt`. */
fun videoDiagFileName(clock: Clock): String = "orbis-video-diagnostics-${LocalDate.now(clock)}.txt"

/** Settings > Video diagnostics. The log below the buttons is exactly what Copy and Save as text file give out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDiagnosticsScreen(
    diagnostics: VideoDiagnostics,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.systemDefaultZone(),
    /** Writes the log's text to the file the user picked; false when it could not be written. */
    writeFile: suspend (Context, Uri, String) -> Boolean = ::writeText,
) {
    val switches by diagnostics.switches.collectAsStateWithLifecycle()
    val version by diagnostics.log.version.collectAsStateWithLifecycle()
    val lines = remember(version) { diagnostics.log.lines() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.video_diag_copied)
    val saved = stringResource(R.string.video_diag_saved)
    val saveFailed = stringResource(R.string.video_diag_save_failed)
    // A cancelled picker (null) says nothing.
    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                val text = diagnostics.log.text()
                scope.launch {
                    val ok = writeFile(context, uri, text)
                    Toast.makeText(context, if (ok) saved else saveFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            OrbisTopAppBar(
                title = { Text(stringResource(R.string.video_diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        SelectionContainer(Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize().testTag("video_diag_list")) {
                item {
                    Text(
                        stringResource(R.string.video_diag_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item {
                    SwitchRow(R.string.video_diag_record, R.string.video_diag_record_summary, switches.recording, true) {
                        diagnostics.setRecording(it)
                    }
                }
                item {
                    SwitchRow(R.string.video_diag_site_names, R.string.video_diag_site_names_summary, switches.includeSiteNames, switches.recording) {
                        diagnostics.setIncludeSiteNames(it)
                    }
                }
                item {
                    SwitchRow(R.string.video_diag_private, R.string.video_diag_private_summary, switches.privateTabs, switches.recording) {
                        diagnostics.setPrivateTabs(it)
                    }
                }
                item {
                    val status =
                        when {
                            lines.isEmpty() -> stringResource(R.string.video_diag_empty)
                            else -> {
                                val size = Formatter.formatShortFileSize(context, diagnostics.log.sizeBytes.toLong())
                                val last = diagnostics.lastEntryAt()
                                val age =
                                    if (last > 0) {
                                        DateUtils.getRelativeTimeSpanString(last, clock.millis(), DateUtils.MINUTE_IN_MILLIS).toString()
                                    } else {
                                        "-"
                                    }
                                stringResource(R.string.video_diag_size_age, size, age)
                            }
                        }
                    Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    if (!switches.recording && lines.isNotEmpty()) {
                        Text(
                            stringResource(R.string.video_diag_off_with_log),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                item {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(enabled = lines.isNotEmpty(), onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Orbis video diagnostics", diagnostics.log.text()))
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.video_diag_copy)) }
                        OutlinedButton(enabled = lines.isNotEmpty(), onClick = { saveLauncher.launch(videoDiagFileName(clock)) }) {
                            Text(stringResource(R.string.video_diag_save))
                        }
                        OutlinedButton(enabled = lines.isNotEmpty(), onClick = diagnostics::deleteLog) {
                            Text(stringResource(R.string.video_diag_delete))
                        }
                    }
                }
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: Int, summary: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** Writes the log's text to the file the user picked. Blocking part on the IO dispatcher. */
private suspend fun writeText(context: Context, uri: Uri, text: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
    }

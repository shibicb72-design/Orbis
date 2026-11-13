package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.about.LicenceEntry
import com.shibi.orbis.about.LicenceFamily
import com.shibi.orbis.about.LicenceKind
import com.shibi.orbis.about.LicenceNote
import com.shibi.orbis.about.LicenceText
import com.shibi.orbis.about.Licences
import com.shibi.orbis.ui.secondaryReadingWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > About > Open-source licences: every component the app ships, its version and licence. A tap shows the
 * full licence text, read once from assets/licenses/ off the main thread and laid out for the screen there
 * ([LicenceText.reflow]: paragraphs wrap, lists and titles keep their layout). Nothing here opens the web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licences_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.licences_back))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.background,
                        titleContentColor = colors.onBackground,
                        navigationIconContentColor = colors.onBackground,
                    ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().secondaryReadingWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Licences.entries, key = { it.name }) { LicenceCard(it) }
        }
    }
}

@Composable
private fun LicenceCard(entry: LicenceEntry) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var expanded by rememberSaveable(entry.name) { mutableStateOf(false) }
    // Loaded once per card, the first time it is opened; an empty list means the asset could not be read.
    var text by remember(entry.name) { mutableStateOf<List<LicenceText.Block>?>(null) }
    if (expanded && text == null) {
        LaunchedEffect(entry.licence) { text = readLicence(context, entry.licence) }
    }
    val secondary = colors.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    val details =
                        listOfNotNull(
                            entry.version?.let { stringResource(R.string.licences_version, it) },
                            stringResource(licenceName(entry.licence.family)),
                        ).joinToString(" · ")
                    Text(details, style = MaterialTheme.typography.bodySmall, color = secondary)
                    entry.note?.let { Text(stringResource(noteText(it)), style = MaterialTheme.typography.bodySmall, color = secondary) }
                    entry.source?.let { Text(stringResource(R.string.licences_source, it), style = MaterialTheme.typography.bodySmall, color = secondary) }
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = secondary,
                )
            }
            if (expanded) {
                val body = text
                when {
                    body == null -> Unit
                    body.isEmpty() -> Text(stringResource(R.string.licences_unavailable), style = MaterialTheme.typography.bodySmall, color = colors.error)
                    else -> LicenceBody(body)
                }
            }
        }
    }
}

@Composable
private fun LicenceBody(blocks: List<LicenceText.Block>) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (blocks.any { it is LicenceText.Block.Preformatted }) {
            Text(stringResource(R.string.licences_preformatted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (block in blocks) {
            when (block) {
                is LicenceText.Block.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodySmall)
                is LicenceText.Block.Bullet ->
                    // The marker hangs; the item's text wraps beside it.
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text(block.marker, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                        Text(block.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                is LicenceText.Block.Preformatted ->
                    // Kept as written: wide lines scroll sideways instead of breaking the layout.
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        softWrap = false,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    )
            }
        }
    }
}

/** On IO, never on main, and laid out there too. Empty when the asset is missing or unreadable. */
private suspend fun readLicence(context: Context, kind: LicenceKind): List<LicenceText.Block> =
    withContext(Dispatchers.IO) {
        val raw = runCatching { context.assets.open(kind.assetPath).bufferedReader().use { it.readText() } }.getOrDefault("")
        if (raw.isBlank()) emptyList() else LicenceText.reflow(raw)
    }

@StringRes
private fun licenceName(family: LicenceFamily): Int =
    when (family) {
        LicenceFamily.BSD_3_CLAUSE -> R.string.licence_bsd_3
        LicenceFamily.MIT -> R.string.licence_mit
        LicenceFamily.BSL_1_0 -> R.string.licence_bsl
        LicenceFamily.APACHE_2_0 -> R.string.licence_apache
        LicenceFamily.MPL_2_0 -> R.string.licence_mpl
    }

@StringRes
private fun noteText(note: LicenceNote): Int =
    when (note) {
        LicenceNote.TORRENT_ENGINE -> R.string.licence_note_torrent
        LicenceNote.INSIDE_LIBTORRENT4J -> R.string.licence_note_libtorrent4j
        LicenceNote.BROWSER_ENGINE -> R.string.licence_note_browser
        LicenceNote.READER_VIEW -> R.string.licence_note_reader
        LicenceNote.APP_FRAMEWORK -> R.string.licence_note_framework
        LicenceNote.QR_SCANNER -> R.string.licence_note_qr
        LicenceNote.YOUTUBE_PIP -> R.string.licence_note_youtube_pip
    }

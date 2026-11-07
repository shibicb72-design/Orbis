package com.shibi.orbis.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.engine.SitePermissionDecisions
import com.shibi.orbis.engine.SitePermissionEntry
import com.shibi.orbis.engine.SitePermissionKind
import com.shibi.orbis.ui.OrbisEmptyState

/**
 * Every camera, microphone and location answer the browser is holding, so a remembered decision can be undone.
 * The origin is shown exactly as stored (punycode), never anything a page supplied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitePermissionsScreen(
    decisions: SitePermissionDecisions,
    onRemoved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by decisions.entries.collectAsStateWithLifecycle()
    // Session answers that expired while this screen was closed should not be listed.
    LaunchedEffect(Unit) { decisions.purgeExpired() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.perm_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            OrbisEmptyState(stringResource(R.string.perm_settings_empty), Icons.Filled.Tune, Modifier.padding(padding))
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    stringResource(R.string.perm_settings_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { "${it.key.kind}|${it.key.origin}|${it.key.embedder}" }) { entry ->
                        EntryRow(
                            entry = entry,
                            onRemove = {
                                decisions.remove(entry.key)
                                onRemoved()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: SitePermissionEntry, onRemove: () -> Unit) {
    val kind =
        stringResource(
            when (entry.key.kind) {
                SitePermissionKind.CAMERA -> R.string.perm_kind_camera
                SitePermissionKind.MICROPHONE -> R.string.perm_kind_microphone
                SitePermissionKind.LOCATION -> R.string.perm_kind_location
            },
        )
    val state = stringResource(if (entry.allow) R.string.perm_state_allowed else R.string.perm_state_denied)
    val parts = mutableListOf(kind, state)
    if (entry.session) parts += stringResource(R.string.perm_state_session)
    entry.key.embedder?.let { parts += stringResource(R.string.perm_embedded_on, it) }
    val removeDescription = stringResource(R.string.perm_remove_description, kind, entry.key.origin)
    ListItem(
        headlineContent = { Text(entry.key.origin) },
        supportingContent = { Text(parts.joinToString(" - ")) },
        trailingContent = {
            TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = removeDescription }) {
                Text(stringResource(R.string.perm_remove))
            }
        },
    )
}

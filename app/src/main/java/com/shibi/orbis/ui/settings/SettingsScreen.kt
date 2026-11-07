package com.shibi.orbis.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.role.BrowserRoleStatus
import com.shibi.orbis.role.DefaultBrowserRole
import com.shibi.orbis.ubo.UboState
import kotlinx.coroutines.flow.StateFlow

@StringRes
private fun searchEngineLabel(engine: SearchEngine): Int =
    when (engine) {
        SearchEngine.DUCKDUCKGO -> R.string.search_engine_duckduckgo
        SearchEngine.GOOGLE -> R.string.search_engine_google
        SearchEngine.BING -> R.string.search_engine_bing
        SearchEngine.BRAVE -> R.string.search_engine_brave
        SearchEngine.STARTPAGE -> R.string.search_engine_startpage
    }

@StringRes
private fun defaultBrowserSummary(status: BrowserRoleStatus): Int =
    when (status) {
        BrowserRoleStatus.HELD -> R.string.default_browser_held
        BrowserRoleStatus.AVAILABLE -> R.string.default_browser_available
        BrowserRoleStatus.UNAVAILABLE -> R.string.default_browser_unavailable
    }

@Composable
private fun SearchEngineDialog(
    selected: SearchEngine,
    onSelect: (SearchEngine) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_engine_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                SearchEngine.entries.forEach { engine ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = engine == selected, role = Role.RadioButton, onClick = { onSelect(engine) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = engine == selected, onClick = null)
                        Text(
                            text = stringResource(searchEngineLabel(engine)),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.search_engine_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uboStateFlow: StateFlow<UboState>,
    searchEngineFlow: StateFlow<SearchEngine>,
    onSelectSearchEngine: (SearchEngine) -> Unit,
    onBack: () -> Unit,
    onOpenErrorLog: () -> Unit,
    onOpenUbo: () -> Unit,
    onOpenSitePermissions: () -> Unit,
    onOpenClearData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uboState by uboStateFlow.collectAsStateWithLifecycle()
    val searchEngine by searchEngineFlow.collectAsStateWithLifecycle()
    var searchDialogOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var roleStatus by remember { mutableStateOf(DefaultBrowserRole.status(context)) }
    // The system dialog is a separate activity: coming back (result or not) re-reads who holds the role.
    val roleLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            roleStatus = DefaultBrowserRole.status(context)
        }
    LifecycleResumeEffect(Unit) {
        roleStatus = DefaultBrowserRole.status(context)
        onPauseOrDispose {}
    }
    if (searchDialogOpen) {
        SearchEngineDialog(
            selected = searchEngine,
            onSelect = {
                onSelectSearchEngine(it)
                searchDialogOpen = false
            },
            onDismiss = { searchDialogOpen = false },
        )
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(R.string.settings_section_browsing) {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.search_engine_title)) },
                    supportingContent = { Text(stringResource(searchEngineLabel(searchEngine))) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { searchDialogOpen = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.default_browser_title)) },
                    supportingContent = { Text(stringResource(defaultBrowserSummary(roleStatus))) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // Re-read first: the answer on screen may be older than the system's.
                            roleStatus = DefaultBrowserRole.status(context)
                            val request = DefaultBrowserRole.requestIntent(context)
                            if (request == null) {
                                // Held or unavailable: say so, nothing to launch.
                                Toast.makeText(context, defaultBrowserSummary(roleStatus), Toast.LENGTH_SHORT).show()
                            } else {
                                runCatching { roleLauncher.launch(request) }.onFailure {
                                    Toast.makeText(context, R.string.default_browser_unavailable, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                )
            }
            SettingsSection(R.string.settings_section_privacy) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        text = stringResource(R.string.settings_etp_note),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.clear_data_title)) },
                    supportingContent = { Text(stringResource(R.string.clear_data_summary)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenClearData),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.perm_settings_title)) },
                    supportingContent = { Text(stringResource(R.string.perm_settings_summary)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSitePermissions),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.ubo_name)) },
                    supportingContent = { Text(uboStatusLine(uboState)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenUbo),
                )
            }
            SettingsSection(R.string.settings_section_support) {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.settings_error_log)) },
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenErrorLog),
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 12.dp).semantics { heading() },
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) { Column { content() } }
    }
}

@Composable
private fun SettingsItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        modifier = modifier.heightIn(min = 64.dp),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

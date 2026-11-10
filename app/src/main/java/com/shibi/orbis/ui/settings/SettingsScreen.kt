package com.shibi.orbis.ui.settings

import android.widget.Toast
import com.shibi.orbis.settings.AppTheme
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.HttpsOnlyMode
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
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
import com.shibi.orbis.about.installedVersionName
import com.shibi.orbis.about.openPlayListing
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
    homepageFlow: StateFlow<String?>,
    onSetHomepage: (String?) -> Unit,
    searchSuggestionsFlow: StateFlow<Boolean>,
    onSetSearchSuggestions: (Boolean) -> Unit,
    textZoomFlow: StateFlow<Int>,
    onSetTextZoom: (Int) -> Unit,
    forceZoomFlow: StateFlow<Boolean>,
    onSetForceZoom: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenErrorLog: () -> Unit,
    onOpenUbo: () -> Unit,
    onOpenSitePermissions: () -> Unit,
    onOpenClearData: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPasswords: () -> Unit = {},
    /** Theme, desktop site, HTTPS-only and JavaScript rows; hidden when null (screen tests). */
    site: BrowserSettings? = null,
    /** Installed version shown under About. Null reads it from the package manager. */
    versionName: String? = null,
    /** About row tap. Null opens the Play listing (market://, then https). */
    onOpenPlayListing: (() -> Unit)? = null,
) {
    var themeDialogOpen by remember { mutableStateOf(false) }
    var httpsDialogOpen by remember { mutableStateOf(false) }
    if (site != null) {
        val theme by site.appTheme.collectAsStateWithLifecycle()
        val https by site.httpsOnly.collectAsStateWithLifecycle()
        if (themeDialogOpen) {
            ChoiceDialog(
                title = R.string.theme_title,
                options = AppTheme.entries.map { it to themeLabel(it) },
                selected = theme,
                onSelect = {
                    site.setAppTheme(it)
                    themeDialogOpen = false
                },
                onDismiss = { themeDialogOpen = false },
            )
        }
        if (httpsDialogOpen) {
            ChoiceDialog(
                title = R.string.https_only_title,
                options = HttpsOnlyMode.entries.map { it to httpsOnlyLabel(it) },
                selected = https,
                onSelect = {
                    site.setHttpsOnly(it)
                    httpsDialogOpen = false
                },
                onDismiss = { httpsDialogOpen = false },
                note = R.string.https_only_note,
            )
        }
    }
    val uboState by uboStateFlow.collectAsStateWithLifecycle()
    val searchEngine by searchEngineFlow.collectAsStateWithLifecycle()
    var searchDialogOpen by remember { mutableStateOf(false) }
    val homepage by homepageFlow.collectAsStateWithLifecycle()
    val searchSuggestions by searchSuggestionsFlow.collectAsStateWithLifecycle()
    val textZoom by textZoomFlow.collectAsStateWithLifecycle()
    val forceZoom by forceZoomFlow.collectAsStateWithLifecycle()
    var textSizeDialogOpen by remember { mutableStateOf(false) }
    if (textSizeDialogOpen) {
        TextSizeDialog(
            current = textZoom,
            onSelect = {
                onSetTextZoom(it)
                textSizeDialogOpen = false
            },
            onDismiss = { textSizeDialogOpen = false },
        )
    }
    var homepageDialogOpen by remember { mutableStateOf(false) }
    if (homepageDialogOpen) {
        HomepageDialog(
            current = homepage,
            onSave = {
                onSetHomepage(it)
                homepageDialogOpen = false
            },
            onDismiss = { homepageDialogOpen = false },
        )
    }
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
                    headlineContent = { Text(stringResource(R.string.search_suggestions_title)) },
                    supportingContent = { Text(stringResource(R.string.search_suggestions_summary)) },
                    trailingContent = { Switch(checked = searchSuggestions, onCheckedChange = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = searchSuggestions,
                            role = Role.Switch,
                            onValueChange = onSetSearchSuggestions,
                        ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.text_size_title)) },
                    supportingContent = { Text(stringResource(R.string.text_size_percent, textZoom)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { textSizeDialogOpen = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.force_zoom_title)) },
                    supportingContent = { Text(stringResource(R.string.force_zoom_summary)) },
                    trailingContent = { Switch(checked = forceZoom, onCheckedChange = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = forceZoom, role = Role.Switch, onValueChange = onSetForceZoom),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (site != null) {
                    val theme by site.appTheme.collectAsStateWithLifecycle()
                    val darkSites by site.darkWebsites.collectAsStateWithLifecycle()
                    val alwaysDesktop by site.alwaysDesktop.collectAsStateWithLifecycle()
                    val forceDark by site.forceDark.collectAsStateWithLifecycle()
                    val openInApps by site.openLinksInApps.collectAsStateWithLifecycle()
                    val toolbarBottom by site.toolbarBottom.collectAsStateWithLifecycle()
                    val copyDownloads by site.copyToDownloadFolder.collectAsStateWithLifecycle()
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.copy_downloads_title)) },
                        supportingContent = { Text(stringResource(R.string.copy_downloads_summary)) },
                        trailingContent = { Switch(checked = copyDownloads, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().toggleable(value = copyDownloads, role = Role.Switch, onValueChange = site::setCopyToDownloadFolder),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.toolbar_bottom_title)) },
                        supportingContent = { Text(stringResource(R.string.toolbar_bottom_summary)) },
                        trailingContent = { Switch(checked = toolbarBottom, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().toggleable(value = toolbarBottom, role = Role.Switch, onValueChange = site::setToolbarBottom),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.theme_title)) },
                        supportingContent = { Text(stringResource(themeLabel(theme))) },
                        modifier = Modifier.fillMaxWidth().clickable { themeDialogOpen = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.dark_websites_title)) },
                        supportingContent = { Text(stringResource(R.string.dark_websites_summary)) },
                        trailingContent = { Switch(checked = darkSites, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().toggleable(value = darkSites, role = Role.Switch, onValueChange = site::setDarkWebsites),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.force_dark_title)) },
                        supportingContent = { Text(stringResource(R.string.force_dark_summary)) },
                        trailingContent = { Switch(checked = forceDark, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().toggleable(value = forceDark, role = Role.Switch, onValueChange = site::setForceDark),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.always_desktop_title)) },
                        supportingContent = { Text(stringResource(R.string.always_desktop_summary)) },
                        trailingContent = { Switch(checked = alwaysDesktop, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().toggleable(value = alwaysDesktop, role = Role.Switch, onValueChange = site::setAlwaysDesktop),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.open_in_apps_title)) },
                        supportingContent = { Text(stringResource(R.string.open_in_apps_summary)) },
                        trailingContent = { Switch(checked = openInApps, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().toggleable(value = openInApps, role = Role.Switch, onValueChange = site::setOpenLinksInApps),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.homepage_title)) },
                    supportingContent = { Text(homepage ?: stringResource(R.string.homepage_start_page)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { homepageDialogOpen = true },
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
                if (site != null) {
                    val https by site.httpsOnly.collectAsStateWithLifecycle()
                    val js by site.javaScript.collectAsStateWithLifecycle()
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.https_only_title)) },
                        supportingContent = { Text(stringResource(httpsOnlyLabel(https))) },
                        modifier = Modifier.fillMaxWidth().clickable { httpsDialogOpen = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.javascript_title)) },
                        supportingContent = { Text(stringResource(R.string.javascript_summary)) },
                        trailingContent = { Switch(checked = js, onCheckedChange = null) },
                        modifier = Modifier.fillMaxWidth().toggleable(value = js, role = Role.Switch, onValueChange = site::setJavaScript),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.passwords_title)) },
                    supportingContent = { Text(stringResource(R.string.passwords_summary)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPasswords),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            val installedVersion = remember { installedVersionName(context) }
            val version = versionName ?: installedVersion
            SettingsSection(R.string.about_title) {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_version, version)) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        val open = onOpenPlayListing
                        if (open != null) open() else openPlayListing(context)
                    },
                )
            }
        }
    }
}

@StringRes
private fun themeLabel(t: AppTheme): Int =
    when (t) {
        AppTheme.SYSTEM -> R.string.theme_system
        AppTheme.LIGHT -> R.string.theme_light
        AppTheme.DARK -> R.string.theme_dark
    }

@StringRes
private fun httpsOnlyLabel(m: HttpsOnlyMode): Int =
    when (m) {
        HttpsOnlyMode.OFF -> R.string.https_only_off
        HttpsOnlyMode.PRIVATE -> R.string.https_only_private
        HttpsOnlyMode.ALL -> R.string.https_only_all
    }

/** A single-choice dialog of radio rows. */
@Composable
private fun <T> ChoiceDialog(
    @StringRes title: Int,
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    @StringRes note: Int? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 16.dp))
                    }
                }
                if (note != null) {
                    Text(stringResource(note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) } },
    )
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
    trailingContent: (@Composable () -> Unit)? = null,
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
        // A row that opens something shows a chevron; a switch row passes its own trailing content.
        trailingContent = trailingContent ?: {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

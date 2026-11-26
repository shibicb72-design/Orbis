package com.shibi.orbis.ui.settings

import android.widget.Toast
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.DohConfig
import com.shibi.orbis.settings.HttpsOnlyMode
import com.shibi.orbis.settings.MagnetHandler
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
import com.shibi.orbis.privacy.PrivacyPrefs
import com.shibi.orbis.ui.isScreenLockSet
import com.shibi.orbis.about.installedVersionName
import com.shibi.orbis.about.openPlayListing
import com.shibi.orbis.engine.SearchProvider
import com.shibi.orbis.role.BrowserRoleStatus
import com.shibi.orbis.role.DefaultBrowserRole
import com.shibi.orbis.ubo.UboState
import com.shibi.orbis.ui.OrbisTopAppBar
import com.shibi.orbis.ui.theme.AppThemes
import kotlinx.coroutines.flow.StateFlow

/**
 * "Lock private tabs": a switch row that toggles anywhere on the row. With no screen lock on the phone there is nothing
 * to unlock with, so the switch is off and disabled and the summary says what to do; it is checked again on every return
 * to this screen (the user may have set a lock in the system settings).
 */
@Composable
internal fun LockPrivateTabsRow(prefs: PrivacyPrefs) {
    val context = LocalContext.current
    var screenLockSet by remember { mutableStateOf(isScreenLockSet(context)) }
    LifecycleResumeEffect(Unit) {
        screenLockSet = isScreenLockSet(context)
        onPauseOrDispose {}
    }
    var on by remember { mutableStateOf(prefs.lockPrivateTabs) }
    val checked = on && screenLockSet
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.private_lock_title)) },
        supportingContent = {
            Text(stringResource(if (screenLockSet) R.string.private_lock_summary else R.string.private_lock_needs_screen_lock))
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = screenLockSet) },
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = screenLockSet,
                    role = Role.Switch,
                    onValueChange = {
                        on = it
                        prefs.lockPrivateTabs = it
                    },
                ),
    )
}

@StringRes
private fun defaultBrowserSummary(status: BrowserRoleStatus): Int =
    when (status) {
        BrowserRoleStatus.HELD -> R.string.default_browser_held
        BrowserRoleStatus.AVAILABLE -> R.string.default_browser_available
        BrowserRoleStatus.UNAVAILABLE -> R.string.default_browser_unavailable
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uboStateFlow: StateFlow<UboState>,
    searchEngineFlow: StateFlow<SearchProvider>,
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
    onOpenBackup: () -> Unit = {},
    /** Theme, desktop site, HTTPS-only and JavaScript rows; hidden when null (screen tests). */
    site: BrowserSettings? = null,
    /** Installed version shown under About. Null reads it from the package manager. */
    versionName: String? = null,
    /** About row tap. Null opens the Play listing (market://, then https). */
    onOpenPlayListing: (() -> Unit)? = null,
    /** About > Open-source licences. */
    onOpenLicences: () -> Unit = {},
    /** Browsing > Torrents (Wi-Fi only, speed limits, seeding, peers, magnet links). */
    onOpenTorrents: () -> Unit = {},
    /** Browsing > Theme: the theme picker (dark mode and colour theme). */
    onOpenThemes: () -> Unit = {},
    /** Privacy > Secure DNS: the DNS-over-HTTPS choice. The row is hidden when null (screen tests). */
    dohFlow: StateFlow<DohConfig>? = null,
    onOpenSecureDns: () -> Unit = {},
    /** Browsing > Search engine: the engine list, where engines can be added and removed. */
    onOpenSearchEngines: () -> Unit = {},
    /** Browsing > Menu and toolbar. The row is hidden when null (screen tests). */
    onOpenMenuToolbar: (() -> Unit)? = null,
    /** Privacy > Add-ons: the curated add-on list. The row is hidden when null (screen tests). */
    onOpenAddons: (() -> Unit)? = null,
    /** Privacy > Clear when Orbis is closed. The row is hidden when null (screen tests). */
    clearOnExitEnabled: Boolean? = null,
    onOpenClearOnExit: () -> Unit = {},
    /** Privacy > Lock private tabs. Hidden when null (screen tests); OrbisRoot passes the app's prefs. */
    privacyPrefs: PrivacyPrefs? = null,
) {
    var httpsDialogOpen by remember { mutableStateOf(false) }
    if (site != null) {
        val https by site.httpsOnly.collectAsStateWithLifecycle()
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
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            OrbisTopAppBar(
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
                    supportingContent = { Text(searchProviderName(searchEngine)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSearchEngines),
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
                    val darkMode by site.darkMode.collectAsStateWithLifecycle()
                    val themeId by site.themeId.collectAsStateWithLifecycle()
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
                    if (onOpenMenuToolbar != null) {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.toolbar_menu_title)) },
                            supportingContent = { Text(stringResource(R.string.toolbar_menu_summary)) },
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenMenuToolbar),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.theme_title)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.theme_summary,
                                    stringResource(AppThemes.byId(themeId).nameRes),
                                    stringResource(darkModeLabel(darkMode)),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenThemes),
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
                    headlineContent = { Text(stringResource(R.string.torrents_settings_title)) },
                    supportingContent = { Text(stringResource(R.string.torrents_settings_row_summary)) },
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenTorrents),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                    ContentTrackersRow(site)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (dohFlow != null) {
                    val doh by dohFlow.collectAsStateWithLifecycle()
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.doh_title)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (doh.resolverUrl == null) R.string.doh_summary_off else dohProviderLabel(doh.provider),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSecureDns),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                    headlineContent = { Text(stringResource(R.string.backup_title)) },
                    supportingContent = { Text(stringResource(R.string.backup_summary)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenBackup),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.clear_data_title)) },
                    supportingContent = { Text(stringResource(R.string.clear_data_summary)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenClearData),
                )
                if (clearOnExitEnabled != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.clear_exit_title)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (clearOnExitEnabled) R.string.clear_exit_summary_on else R.string.clear_exit_summary_off,
                                ),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenClearOnExit),
                    )
                }
                if (privacyPrefs != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LockPrivateTabsRow(privacyPrefs)
                }
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
                if (onOpenAddons != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.addons_title)) },
                        supportingContent = { Text(stringResource(R.string.addons_settings_summary)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenAddons),
                    )
                }
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.licences_title)) },
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLicences),
                )
            }
        }
    }
}

@StringRes
internal fun darkModeLabel(m: DarkMode): Int =
    when (m) {
        DarkMode.SYSTEM -> R.string.theme_system
        DarkMode.LIGHT -> R.string.theme_light
        DarkMode.DARK -> R.string.theme_dark
    }

@StringRes
internal fun magnetHandlerLabel(h: MagnetHandler): Int =
    when (h) {
        MagnetHandler.ORBIS -> R.string.torrents_setting_magnets_orbis
        MagnetHandler.OTHER_APP -> R.string.torrents_setting_magnets_other_app
    }

@StringRes
private fun httpsOnlyLabel(m: HttpsOnlyMode): Int =
    when (m) {
        HttpsOnlyMode.OFF -> R.string.https_only_off
        HttpsOnlyMode.PRIVATE -> R.string.https_only_private
        HttpsOnlyMode.ALL -> R.string.https_only_all
    }

/** "Also block content trackers": a switch row that toggles anywhere on the row. Off by default. */
@Composable
internal fun ContentTrackersRow(site: BrowserSettings) {
    val on by site.blockContentTrackers.collectAsStateWithLifecycle()
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.content_trackers_title)) },
        supportingContent = { Text(stringResource(R.string.content_trackers_summary)) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = site::setBlockContentTrackers),
    )
}

/** A single-choice dialog of radio rows. */
@Composable
internal fun <T> ChoiceDialog(
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
internal fun SettingsSection(@StringRes title: Int, content: @Composable () -> Unit) {
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
internal fun SettingsItem(
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

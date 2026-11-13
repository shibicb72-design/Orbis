package com.shibi.orbis.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.MagnetHandler
import com.shibi.orbis.torrent.TorrentSettings
import com.shibi.orbis.torrent.TorrentSettingsStore

private enum class TorrentDialog { DOWN_LIMIT, UP_LIMIT, RATIO, MAGNETS, IP_NOTE }

/**
 * Settings > Torrents. Every change is stored and applied at once (no Save button): the torrent manager follows the
 * store's flow, so caps, discovery and Wi-Fi only change while torrents run. App-wide, private tabs included.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentSettingsScreen(
    store: TorrentSettingsStore,
    browser: BrowserSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val s by store.settings.collectAsStateWithLifecycle()
    val magnets by browser.magnetHandler.collectAsStateWithLifecycle()
    val rows = TorrentSettingsRowModel.from(s)
    var dialog by rememberSaveable { mutableStateOf<TorrentDialog?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.torrents_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSection(R.string.torrents_settings_section_network) {
                SwitchRow(R.string.torrents_settings_wifi_only, R.string.torrents_settings_wifi_only_summary, s.wifiOnly) { on ->
                    store.update { it.copy(wifiOnly = on) }
                }
                Divider()
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.torrents_settings_down_limit)) },
                    supportingContent = { Text(limitText(rows.down)) },
                    modifier = Modifier.fillMaxWidth().clickable { dialog = TorrentDialog.DOWN_LIMIT },
                )
                Divider()
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.torrents_settings_up_limit)) },
                    supportingContent = { Text(limitText(rows.up)) },
                    modifier = Modifier.fillMaxWidth().clickable { dialog = TorrentDialog.UP_LIMIT },
                )
            }
            SettingsSection(R.string.torrents_settings_section_seeding) {
                SwitchRow(R.string.torrents_settings_stop_seeding, R.string.torrents_settings_stop_seeding_summary, s.stopSeeding) { on ->
                    store.update { it.copy(stopSeeding = on) }
                }
                if (rows.ratioRowVisible) {
                    Divider()
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.torrents_settings_ratio)) },
                        supportingContent = {
                            Column {
                                Text(rows.ratio ?: stringResource(R.string.torrents_settings_ratio_none))
                                Text(stringResource(R.string.torrents_settings_ratio_summary))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().clickable { dialog = TorrentDialog.RATIO },
                    )
                }
            }
            SettingsSection(R.string.torrents_settings_section_peers) {
                SwitchRow(R.string.torrents_settings_dht, R.string.torrents_settings_dht_summary, s.dht) { on -> store.update { it.copy(dht = on) } }
                Divider()
                SwitchRow(R.string.torrents_settings_pex, R.string.torrents_settings_pex_summary, s.pex) { on -> store.update { it.copy(pex = on) } }
                Divider()
                SwitchRow(R.string.torrents_settings_lsd, R.string.torrents_settings_lsd_summary, s.lsd) { on -> store.update { it.copy(lsd = on) } }
            }
            SettingsSection(R.string.torrents_settings_section_links) {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.torrents_setting_magnets_title)) },
                    supportingContent = { Text(stringResource(magnetHandlerLabel(magnets))) },
                    modifier = Modifier.fillMaxWidth().clickable { dialog = TorrentDialog.MAGNETS },
                )
            }
            SettingsSection(R.string.torrents_settings_section_privacy) {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.torrents_settings_ip_title)) },
                    modifier = Modifier.fillMaxWidth().clickable { dialog = TorrentDialog.IP_NOTE },
                )
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        TorrentDialog.DOWN_LIMIT ->
            SpeedLimitDialog(R.string.torrents_settings_down_limit, TorrentSettings.bpsToKbps(s.downBps), close) { kbps ->
                store.update { it.copy(downBps = TorrentSettings.kbpsToBps(kbps)) }
                close()
            }
        TorrentDialog.UP_LIMIT ->
            SpeedLimitDialog(R.string.torrents_settings_up_limit, TorrentSettings.bpsToKbps(s.upBps), close) { kbps ->
                store.update { it.copy(upBps = TorrentSettings.kbpsToBps(kbps)) }
                close()
            }
        TorrentDialog.RATIO ->
            RatioDialog(rows.ratio, close) { ratio ->
                store.update { it.copy(seedRatioLimit = ratio) }
                close()
            }
        TorrentDialog.MAGNETS ->
            ChoiceDialog(
                title = R.string.torrents_setting_magnets_title,
                options = MagnetHandler.entries.map { it to magnetHandlerLabel(it) },
                selected = magnets,
                onSelect = {
                    browser.setMagnetHandler(it)
                    close()
                },
                onDismiss = close,
            )
        TorrentDialog.IP_NOTE -> IpNoteDialog(onReset = { browser.resetTorrentIpNote(); close() }, onDismiss = close)
        null -> Unit
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes summary: Int, value: Boolean, onChange: (Boolean) -> Unit) {
    SettingsItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = value, onCheckedChange = null) },
        modifier = Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun limitText(label: LimitLabel): String =
    when (label) {
        LimitLabel.NoLimit -> stringResource(R.string.torrents_settings_no_limit)
        is LimitLabel.Kbps -> stringResource(R.string.torrents_settings_kbps, label.kbps)
    }

/** One speed cap in KB/s, 0 (or empty) for no limit. Save is enabled only for a number in 0..1,000,000. */
@Composable
private fun SpeedLimitDialog(@StringRes title: Int, currentKbps: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(if (currentKbps > 0) currentKbps.toString() else "") }
    val parsed = TorrentSettingsRowModel.parseKbps(text)
    val onSurface = MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = onSurface,
        textContentColor = onSurface,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(9) },
                singleLine = true,
                label = { Text(stringResource(R.string.torrents_settings_limit_field)) },
                isError = parsed == null,
                supportingText = {
                    when {
                        parsed == null -> Text(stringResource(R.string.torrents_settings_limit_error))
                        parsed == 0 -> Text(stringResource(R.string.torrents_settings_no_limit))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onSave) },
                enabled = parsed != null,
                colors = ButtonDefaults.textButtonColors(contentColor = onSurface),
            ) { Text(stringResource(R.string.torrents_settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = onSurface)) {
                Text(stringResource(R.string.torrents_cancel))
            }
        },
    )
}

/** The seeding ratio limit: one decimal in 0.1..100, or "No limit". */
@Composable
private fun RatioDialog(current: String?, onDismiss: () -> Unit, onSave: (Float?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current ?: "1.0") }
    val parsed = TorrentSettingsRowModel.parseRatio(text)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val buttons = ButtonDefaults.textButtonColors(contentColor = onSurface)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = onSurface,
        textContentColor = onSurface,
        title = { Text(stringResource(R.string.torrents_settings_ratio)) },
        text = {
            Column {
                Text(stringResource(R.string.torrents_settings_ratio_summary), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(6) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.torrents_settings_ratio_field)) },
                    isError = parsed == null,
                    supportingText = { if (parsed == null) Text(stringResource(R.string.torrents_settings_ratio_error)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null, colors = buttons) {
                Text(stringResource(R.string.torrents_settings_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }, colors = buttons) { Text(stringResource(R.string.torrents_settings_ratio_none)) }
                TextButton(onClick = onDismiss, colors = buttons) { Text(stringResource(R.string.torrents_cancel)) }
            }
        },
    )
}

/** The confirm sheet's full IP note, what discovery and seeding add, and a way to see the note on the sheet again. */
@Composable
private fun IpNoteDialog(onReset: () -> Unit, onDismiss: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val buttons = ButtonDefaults.textButtonColors(contentColor = onSurface)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = onSurface,
        textContentColor = onSurface,
        title = { Text(stringResource(R.string.torrents_settings_ip_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.torrents_sheet_ip_note))
                Text(stringResource(R.string.torrents_settings_ip_more), modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, colors = buttons) { Text(stringResource(R.string.torrents_settings_close)) } },
        dismissButton = { TextButton(onClick = onReset, colors = buttons) { Text(stringResource(R.string.torrents_settings_ip_reset)) } },
    )
}

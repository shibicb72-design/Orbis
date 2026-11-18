package com.shibi.orbis.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.addons.AddonCatalog
import com.shibi.orbis.addons.AddonFailure
import com.shibi.orbis.addons.AddonManager
import com.shibi.orbis.addons.AddonState
import com.shibi.orbis.addons.CatalogAddon
import com.shibi.orbis.engine.AddonAccess
import com.shibi.orbis.ui.OrbisTopAppBar
import kotlinx.coroutines.CompletableDeferred

/**
 * The question "Add X?" while an install waits for the user. [AddonManager.install] (and every add-on action queued
 * behind it) waits for its `confirm` with no time limit, so [ask] must always return: it answers false when the dialog
 * is dismissed, when the user leaves ([answer]), when the coroutine is cancelled, and, once [close]d, at once. The
 * question lives here, not in the dialog, so a dialog that leaves composition cannot strand it.
 */
internal class AddonInstallPrompt {
    class Pending(val name: String, val access: AddonAccess) {
        val answer = CompletableDeferred<Boolean>()
    }

    /** The open question, or null. The screen shows the dialog while this is set. */
    var pending by mutableStateOf<Pending?>(null)
        private set

    @Volatile private var closed = false

    suspend fun ask(name: String, access: AddonAccess): Boolean {
        if (closed) return false
        val question = Pending(name, access)
        pending?.answer?.complete(false)
        pending = question
        try {
            return question.answer.await()
        } finally {
            question.answer.complete(false)
            if (pending === question) pending = null
        }
    }

    /** The user's answer to the open question; false on dismiss. */
    fun answer(yes: Boolean) {
        pending?.answer?.complete(yes)
    }

    /** The screen is gone: answer any open question with false, and refuse every later one. */
    fun close() {
        closed = true
        pending?.answer?.complete(false)
    }
}

/** The "Can access:" line per add-on, by id. The catalogue's own text is for developers and is not shown. */
@StringRes
private fun accessSummaryRes(id: String): Int? =
    when (id) {
        "sponsorBlocker@ajay.app" -> R.string.addons_access_sponsorblock
        "gdpr@cavi.au.dk" -> R.string.addons_access_consent_o_matic
        "{74145f27-f039-47ce-a470-a662b129930a}" -> R.string.addons_access_clearurls
        "addon@darkreader.org" -> R.string.addons_access_dark_reader
        "{aecec67f-0d10-4fa7-b7c7-609a2db280cf}" -> R.string.addons_access_violentmonkey
        else -> null
    }

@StringRes
private fun failureRes(reason: AddonFailure): Int =
    when (reason) {
        AddonFailure.NETWORK -> R.string.addons_failed_network
        AddonFailure.TIMEOUT -> R.string.addons_failed_timeout
        AddonFailure.SIGNATURE -> R.string.addons_failed_signature
        AddonFailure.BLOCKED -> R.string.addons_failed_blocked
        AddonFailure.INCOMPATIBLE -> R.string.addons_failed_incompatible
        AddonFailure.CORRUPT -> R.string.addons_failed_corrupt
        AddonFailure.WRONG_EXTENSION -> R.string.addons_failed_wrong_extension
        AddonFailure.UNKNOWN -> R.string.addons_failed_other
    }

/** The curated add-ons: what each can access, the install question, and the on, private-tab, settings and remove controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonsScreen(
    manager: AddonManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens an add-on's own settings page (moz-extension://...) in a tab. */
    onOpenExtensionPage: (String) -> Unit = {},
) {
    val states by manager.states.collectAsStateWithLifecycle()
    val prompt = remember { AddonInstallPrompt() }
    DisposableEffect(prompt) { onDispose { prompt.close() } }
    LaunchedEffect(manager) { manager.refresh() }
    var removing by remember { mutableStateOf<CatalogAddon?>(null) }
    val anyInstalling = states.values.any { it == AddonState.Installing }

    prompt.pending?.let { question ->
        AlertDialog(
            onDismissRequest = { prompt.answer(false) },
            title = { Text(stringResource(R.string.addons_add_title, question.name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.addons_asks_for), style = MaterialTheme.typography.titleSmall)
                    val lines = question.access.permissions + question.access.origins
                    if (lines.isEmpty()) {
                        Text(stringResource(R.string.addons_asks_nothing), style = MaterialTheme.typography.bodyMedium)
                    }
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { prompt.answer(true) }) { Text(stringResource(R.string.addons_add)) } },
            dismissButton = { TextButton(onClick = { prompt.answer(false) }) { Text(stringResource(R.string.addons_cancel)) } },
        )
    }
    removing?.let { addon ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.addons_remove_title, addon.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager.remove(addon.id)
                        removing = null
                    },
                ) { Text(stringResource(R.string.addons_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.addons_cancel)) } },
        )
    }

    val colors = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            OrbisTopAppBar(
                containerColor = colors.background,
                contentColor = colors.onBackground,
                title = { Text(stringResource(R.string.addons_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            prompt.answer(false)
                            onBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val (regular, advanced) = AddonCatalog.entries.partition { !it.advanced }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.addons_note), style = MaterialTheme.typography.bodyMedium)
            regular.forEach { addon ->
                AddonRow(
                    addon = addon,
                    state = states[addon.id],
                    addEnabled = !anyInstalling,
                    onAdd = { startInstall(manager, prompt, addon) },
                    onRemove = { removing = addon },
                    onOpenExtensionPage = onOpenExtensionPage,
                    manager = manager,
                )
            }
            if (advanced.isNotEmpty()) {
                Text(
                    stringResource(R.string.addons_advanced),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                advanced.forEach { addon ->
                    AddonRow(
                        addon = addon,
                        state = states[addon.id],
                        addEnabled = !anyInstalling,
                        onAdd = { startInstall(manager, prompt, addon) },
                        onRemove = { removing = addon },
                        onOpenExtensionPage = onOpenExtensionPage,
                        manager = manager,
                    )
                }
            }
        }
    }
}

private fun startInstall(manager: AddonManager, prompt: AddonInstallPrompt, addon: CatalogAddon) {
    manager.install(addon.id) { access -> prompt.ask(addon.name, access) }
}

@Composable
private fun AddonRow(
    addon: CatalogAddon,
    state: AddonState?,
    addEnabled: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onOpenExtensionPage: (String) -> Unit,
    manager: AddonManager,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(addon.name, style = MaterialTheme.typography.titleMedium)
        accessSummaryRes(addon.id)?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
        }
        when (state) {
            // Not known yet: no button until the engine's list arrives.
            null -> Unit
            AddonState.Installing ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.addons_installing), style = MaterialTheme.typography.bodyMedium)
                }
            AddonState.NotInstalled ->
                Button(onClick = onAdd, enabled = addEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.addons_add))
                }
            is AddonState.Failed -> {
                Text(
                    stringResource(failureRes(state.reason)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onAdd, enabled = addEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.addons_try_again))
                }
            }
            is AddonState.Installed -> {
                if (state.disabledByBrowser) {
                    // The engine turned it off (blocklist or signature): nothing here can switch it back on.
                    Text(
                        stringResource(R.string.addons_turned_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (state.disabledOther) {
                    // Off for a reason the engine does not name as the blocklist or signature: no claim about safety.
                    Text(
                        stringResource(R.string.addons_turned_off_other),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    SwitchRow(R.string.addons_on, state.enabled) { manager.setEnabled(addon.id, it) }
                    SwitchRow(R.string.addons_allow_private, state.allowedInPrivate) { manager.setAllowedInPrivate(addon.id, it) }
                }
                if (state.updateBlocked) {
                    Text(
                        stringResource(R.string.addons_update_blocked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val options = state.optionsPageUrl
                if (options != null && !state.disabledByBrowser && !state.disabledOther) {
                    OutlinedButton(onClick = { onOpenExtensionPage(options) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.addons_open_settings))
                    }
                }
                OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.addons_remove))
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

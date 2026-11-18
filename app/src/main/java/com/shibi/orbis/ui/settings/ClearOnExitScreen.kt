package com.shibi.orbis.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.clear.CLEAR_ON_EXIT_MAX_EXCEPTIONS
import com.shibi.orbis.clear.ClearOnExitPrefs
import com.shibi.orbis.clear.clearOnExitDomain
import com.shibi.orbis.ui.OrbisTopAppBar

/** What adding a typed site to the exceptions list did. */
sealed interface AddSiteResult {
    /** Stored as [domain]; [sites] is the new list. */
    data class Added(val domain: String, val sites: Set<String>) : AddSiteResult

    /** The site was already listed; nothing changed. */
    data object Duplicate : AddSiteResult

    /** The input is not a host name (or is an IP address). */
    data object Invalid : AddSiteResult

    /** The list already holds [CLEAR_ON_EXIT_MAX_EXCEPTIONS] sites. */
    data object Full : AddSiteResult
}

/** Checks and normalises [input] with [clearOnExitDomain] (the one normaliser) and adds it to [current]. */
fun addClearOnExitSite(current: Set<String>, input: String): AddSiteResult {
    val domain = clearOnExitDomain(input) ?: return AddSiteResult.Invalid
    if (domain in current) return AddSiteResult.Duplicate
    if (current.size >= CLEAR_ON_EXIT_MAX_EXCEPTIONS) return AddSiteResult.Full
    return AddSiteResult.Added(domain, (current + domain).toSortedSet())
}

/** Settings > Clear when Orbis is closed: the switch and the sites whose cookies are kept. Every change is stored at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearOnExitScreen(prefs: ClearOnExitPrefs, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var sites by remember { mutableStateOf(prefs.exceptions.toSortedSet().toList()) }
    var input by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val full = sites.size >= CLEAR_ON_EXIT_MAX_EXCEPTIONS

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            OrbisTopAppBar(
                title = { Text(stringResource(R.string.clear_exit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                containerColor = colors.background,
                contentColor = colors.onBackground,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            // The whole row toggles, as on the other switch rows (DohScreen): the switch itself only shows the state.
            ListItem(
                headlineContent = { Text(stringResource(R.string.clear_exit_switch)) },
                trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
                modifier =
                    Modifier.fillMaxWidth()
                        .toggleable(
                            value = enabled,
                            role = Role.Switch,
                            onValueChange = {
                                enabled = it
                                prefs.enabled = it
                            },
                        ),
            )
            Text(
                stringResource(R.string.clear_exit_note),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = colors.outlineVariant)
            SettingsSection(R.string.clear_exit_sites_title) {
                Text(
                    stringResource(R.string.clear_exit_sites_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it.take(300)
                            showError = false
                        },
                        label = { Text(stringResource(R.string.clear_exit_site_hint)) },
                        singleLine = true,
                        isError = showError,
                        supportingText = if (showError) ({ Text(stringResource(R.string.clear_exit_error)) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !full,
                        modifier = Modifier.padding(top = 8.dp),
                        onClick = {
                            when (val r = addClearOnExitSite(sites.toSet(), input)) {
                                is AddSiteResult.Added -> {
                                    prefs.exceptions = r.sites
                                    sites = r.sites.toList()
                                    input = ""
                                    showError = false
                                }
                                AddSiteResult.Duplicate -> {
                                    input = ""
                                    showError = false
                                }
                                AddSiteResult.Invalid -> showError = true
                                AddSiteResult.Full -> Unit
                            }
                        },
                    ) { Text(stringResource(R.string.clear_exit_add)) }
                }
                if (full) {
                    Text(
                        stringResource(R.string.clear_exit_full),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                sites.forEach { site ->
                    HorizontalDivider(color = colors.outlineVariant)
                    SiteRow(site) {
                        val next = sites.toSet() - site
                        prefs.exceptions = next
                        sites = next.toSortedSet().toList()
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteRow(site: String, onRemove: () -> Unit) {
    val description = stringResource(R.string.clear_exit_remove_description, site)
    ListItem(
        headlineContent = { Text(site) },
        trailingContent = {
            TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = description }) {
                Text(stringResource(R.string.clear_exit_remove))
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

package com.shibi.orbis.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.settings.DohProvider
import com.shibi.orbis.settings.DohSettings
import com.shibi.orbis.ui.OrbisTopAppBar

@StringRes
internal fun dohProviderLabel(provider: DohProvider): Int =
    when (provider) {
        DohProvider.QUAD9 -> R.string.doh_provider_quad9
        DohProvider.CLOUDFLARE -> R.string.doh_provider_cloudflare
        DohProvider.MULLVAD -> R.string.doh_provider_mullvad
        DohProvider.CUSTOM -> R.string.doh_provider_custom
    }

/** Settings > Secure DNS. Every change is stored at once; the engine follows [DohSettings.config]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DohScreen(settings: DohSettings, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val config by settings.config.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(config.customUrl.orEmpty()) }
    var addressError by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            OrbisTopAppBar(
                title = { Text(stringResource(R.string.doh_title)) },
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
            Surface(color = colors.surfaceContainer, contentColor = colors.onSurface) {
                Text(
                    text = stringResource(R.string.doh_note),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.doh_switch)) },
                trailingContent = { Switch(checked = config.enabled, onCheckedChange = null) },
                modifier =
                    Modifier.fillMaxWidth()
                        .toggleable(value = config.enabled, role = Role.Switch, onValueChange = settings::setEnabled),
            )
            if (config.enabled) {
                for (provider in DohProvider.entries) {
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingsItem(
                        headlineContent = { Text(stringResource(dohProviderLabel(provider))) },
                        trailingContent = { RadioButton(selected = config.provider == provider, onClick = null) },
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = config.provider == provider,
                                    role = Role.RadioButton,
                                    onClick = { settings.setProvider(provider) },
                                ),
                    )
                }
                if (config.provider == DohProvider.CUSTOM) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            addressError = false
                        },
                        label = { Text(stringResource(R.string.doh_custom_label)) },
                        singleLine = true,
                        isError = addressError,
                        supportingText = if (addressError) ({ Text(stringResource(R.string.doh_custom_error)) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (config.resolverUrl == null) {
                        Text(
                            text = stringResource(R.string.doh_custom_pending),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    TextButton(
                        onClick = { addressError = !settings.setCustomUrl(address) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.doh_custom_save))
                    }
                }
            }
        }
    }
}

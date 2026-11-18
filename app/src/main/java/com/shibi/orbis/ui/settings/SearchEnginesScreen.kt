package com.shibi.orbis.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.shibi.orbis.engine.AddResult
import com.shibi.orbis.engine.CustomEngineError
import com.shibi.orbis.engine.MAX_CUSTOM_ENGINES
import com.shibi.orbis.engine.MAX_CUSTOM_NAME
import com.shibi.orbis.engine.MAX_CUSTOM_TEMPLATE
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.engine.SearchEngineSettings
import com.shibi.orbis.engine.SearchProvider
import com.shibi.orbis.ui.OrbisTopAppBar

@StringRes
private fun builtInLabel(engine: SearchEngine): Int =
    when (engine) {
        SearchEngine.DUCKDUCKGO -> R.string.search_engine_duckduckgo
        SearchEngine.GOOGLE -> R.string.search_engine_google
        SearchEngine.BING -> R.string.search_engine_bing
        SearchEngine.BRAVE -> R.string.search_engine_brave
        SearchEngine.STARTPAGE -> R.string.search_engine_startpage
    }

/** The name shown for [provider]: the built-in label, or the name the user gave. */
@Composable
internal fun searchProviderName(provider: SearchProvider): String =
    when (provider) {
        is SearchProvider.BuiltIn -> stringResource(builtInLabel(provider.engine))
        is SearchProvider.Custom -> provider.name
    }

@StringRes
private fun errorText(error: CustomEngineError): Int =
    when (error) {
        CustomEngineError.NOT_HTTPS -> R.string.search_engines_error_https
        CustomEngineError.PLACEHOLDER -> R.string.search_engines_error_placeholder
        CustomEngineError.BAD_ADDRESS -> R.string.search_engines_error_address
        CustomEngineError.NAME_BLANK -> R.string.search_engines_error_name
        CustomEngineError.NAME_USED -> R.string.search_engines_error_name_used
        CustomEngineError.TOO_MANY -> R.string.search_engines_error_limit
    }

private fun CustomEngineError.isAboutName(): Boolean =
    this == CustomEngineError.NAME_BLANK || this == CustomEngineError.NAME_USED

/** Settings > Search engine: the built-in engines and the ones the user added. Every change is stored at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchEnginesScreen(settings: SearchEngineSettings, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val selected by settings.selected.collectAsStateWithLifecycle()
    val custom by settings.custom.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    if (adding) AddSearchEngineDialog(settings = settings, onDone = { adding = false })

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            OrbisTopAppBar(
                title = { Text(stringResource(R.string.search_engine_title)) },
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
                    text = stringResource(R.string.search_engine_note) + "\n\n" + stringResource(R.string.search_engines_note),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            SettingsSection(R.string.search_engine_dialog_title) {
                Column(Modifier.selectableGroup()) {
                    val all: List<SearchProvider> = SearchProvider.BUILT_INS + custom
                    all.forEachIndexed { i, provider ->
                        if (i > 0) HorizontalDivider(color = colors.outlineVariant)
                        EngineRow(
                            provider = provider,
                            selected = provider.id == selected.id,
                            onSelect = { settings.select(provider) },
                            onRemove = if (provider is SearchProvider.Custom) ({ settings.removeCustom(provider.id) }) else null,
                        )
                    }
                }
            }
            val full = custom.size >= MAX_CUSTOM_ENGINES
            TextButton(
                onClick = { adding = true },
                enabled = !full,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.search_engines_add))
            }
            if (full) {
                Text(
                    text = stringResource(R.string.search_engines_error_limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun EngineRow(provider: SearchProvider, selected: Boolean, onSelect: () -> Unit, onRemove: (() -> Unit)?) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(searchProviderName(provider)) },
        supportingContent = (provider as? SearchProvider.Custom)?.let { c -> { Text(c.template, maxLines = 2) } },
        trailingContent = onRemove?.let { remove ->
            { TextButton(onClick = remove) { Text(stringResource(R.string.search_engines_remove)) } }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

@Composable
private fun AddSearchEngineDialog(settings: SearchEngineSettings, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<CustomEngineError?>(null) }
    val nameError = error?.takeIf { it.isAboutName() }
    val addressError = error?.takeUnless { it.isAboutName() }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.search_engines_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(MAX_CUSTOM_NAME)
                        error = null
                    },
                    label = { Text(stringResource(R.string.search_engines_name)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { e -> { Text(stringResource(errorText(e))) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it.take(MAX_CUSTOM_TEMPLATE)
                        error = null
                    },
                    label = { Text(stringResource(R.string.search_engines_address)) },
                    singleLine = true,
                    isError = addressError != null,
                    supportingText = addressError?.let { e -> { Text(stringResource(errorText(e))) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val r = settings.addCustom(name, address)) {
                        is AddResult.Added -> onDone()
                        is AddResult.Refused -> error = r.error
                    }
                },
            ) { Text(stringResource(R.string.search_engines_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.prompt_cancel)) } },
    )
}

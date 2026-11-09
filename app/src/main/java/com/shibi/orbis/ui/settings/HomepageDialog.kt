package com.shibi.orbis.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.settings.parseHomepageInput

internal const val HOMEPAGE_FIELD_TAG = "homepage_field"

/** Choose the Orbis start page or a custom address. [onSave] gets null for the start page. */
@Composable
internal fun HomepageDialog(current: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var custom by rememberSaveable { mutableStateOf(current != null) }
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.homepage_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                listOf(false to R.string.homepage_start_page, true to R.string.homepage_custom).forEach { (isCustom, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = custom == isCustom, role = Role.RadioButton, onClick = { custom = isCustom })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = custom == isCustom, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 16.dp))
                    }
                }
                if (custom) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            invalid = false
                        },
                        singleLine = true,
                        isError = invalid,
                        label = { Text(stringResource(R.string.homepage_custom_hint)) },
                        supportingText = if (invalid) ({ Text(stringResource(R.string.homepage_invalid)) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag(HOMEPAGE_FIELD_TAG),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!custom) {
                    onSave(null)
                } else {
                    val url = parseHomepageInput(text)
                    if (url == null) invalid = true else onSave(url)
                }
            }) { Text(stringResource(R.string.homepage_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) } },
    )
}

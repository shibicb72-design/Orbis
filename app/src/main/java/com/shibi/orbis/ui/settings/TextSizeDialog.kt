package com.shibi.orbis.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shibi.orbis.R
import com.shibi.orbis.settings.DEFAULT_TEXT_ZOOM
import com.shibi.orbis.settings.TEXT_ZOOM_STEPS

/** Page text size as a percentage; the sample line under each choice is drawn at that size. */
@Composable
internal fun TextSizeDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val base = MaterialTheme.typography.bodyMedium
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_size_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                TEXT_ZOOM_STEPS.forEach { percent ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = percent == current, role = Role.RadioButton, onClick = { onSelect(percent) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = percent == current, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(
                                if (percent == DEFAULT_TEXT_ZOOM) {
                                    stringResource(R.string.text_size_default, percent)
                                } else {
                                    stringResource(R.string.text_size_percent, percent)
                                },
                            )
                            if (percent == current) {
                                Text(
                                    stringResource(R.string.text_size_sample),
                                    style = base.copy(fontSize = base.fontSize * (percent / 100f)),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_cancel)) } },
    )
}

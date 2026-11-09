package com.shibi.orbis.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.engine.SaveLoginChoice
import com.shibi.orbis.engine.FilePickStage
import com.shibi.orbis.engine.SitePermissionKind
import com.shibi.orbis.engine.SitePrompt

/**
 * The dialog for one site prompt. The title names the origin the browser itself committed ([SitePrompt.originHost]);
 * page-supplied text only ever appears in the body. Date, time and colour prompts have their own pickers
 * (FormPickerDialogs); a file pick uses the system picker (see BrowserScreen) and only shows its progress here.
 */
@Composable
fun SitePromptDialog(prompt: SitePrompt) {
    val title =
        if (prompt.originHost.isNotEmpty()) {
            stringResource(R.string.prompt_title_origin, prompt.originHost)
        } else {
            stringResource(R.string.prompt_title_page)
        }
    when (prompt) {
        is SitePrompt.Alert ->
            PromptDialog(
                title = title,
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = prompt::acknowledge) { Text(stringResource(R.string.prompt_ok)) } },
            ) { Text(prompt.message) }

        is SitePrompt.Confirm ->
            PromptDialog(
                title = title,
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = prompt::accept) { Text(stringResource(R.string.prompt_ok)) } },
                dismissButton = { TextButton(onClick = prompt::reject) { Text(stringResource(R.string.prompt_cancel)) } },
            ) { Text(prompt.message) }

        is SitePrompt.TextInput -> {
            var text by remember(prompt) { mutableStateOf(prompt.defaultValue) }
            PromptDialog(
                title = title,
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = { prompt.submit(text) }) { Text(stringResource(R.string.prompt_ok)) } },
                dismissButton = { TextButton(onClick = prompt::dismiss) { Text(stringResource(R.string.prompt_cancel)) } },
            ) {
                Column {
                    if (prompt.message.isNotEmpty()) Text(prompt.message, Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        is SitePrompt.Choice -> ChoiceDialog(prompt, title)

        is SitePrompt.Auth -> {
            var user by remember(prompt) { mutableStateOf(prompt.username) }
            var password by remember(prompt) { mutableStateOf("") }
            PromptDialog(
                title =
                    if (prompt.originHost.isNotEmpty()) {
                        stringResource(R.string.prompt_auth_title, prompt.originHost)
                    } else {
                        stringResource(R.string.prompt_auth_title_generic)
                    },
                onDismiss = prompt::dismiss,
                confirmButton = {
                    TextButton(onClick = { prompt.submit(user, password) }) {
                        Text(stringResource(R.string.prompt_auth_sign_in))
                    }
                },
                dismissButton = { TextButton(onClick = prompt::dismiss) { Text(stringResource(R.string.prompt_cancel)) } },
            ) {
                Column {
                    if (prompt.message.isNotEmpty()) Text(prompt.message, Modifier.padding(bottom = 8.dp))
                    if (prompt.previousFailed) {
                        Text(
                            stringResource(R.string.prompt_auth_previous_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (!prompt.passwordOnly) {
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.prompt_auth_username)) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.prompt_auth_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is SitePrompt.BeforeUnload ->
            PromptDialog(
                title = title,
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = prompt::leave) { Text(stringResource(R.string.prompt_leave)) } },
                dismissButton = { TextButton(onClick = prompt::stay) { Text(stringResource(R.string.prompt_stay)) } },
            ) { Text(stringResource(R.string.prompt_leave_message)) }

        is SitePrompt.OpenInApp ->
            PromptDialog(
                title =
                    if (prompt.appLabel != null) {
                        stringResource(R.string.open_app_title, prompt.appLabel)
                    } else {
                        stringResource(R.string.open_app_title_any)
                    },
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = prompt::open) { Text(stringResource(R.string.open_app_open)) } },
                dismissButton = {
                    TextButton(onClick = prompt::stay) {
                        Text(stringResource(if (prompt.webLink) R.string.open_app_stay else R.string.prompt_cancel))
                    }
                },
            ) {
                Text(
                    stringResource(if (prompt.fromPrivateTab) R.string.open_app_body_private else R.string.open_app_body, prompt.originHost),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        is SitePrompt.SaveLogin ->
            PromptDialog(
                title = stringResource(if (prompt.isUpdate) R.string.login_update_title else R.string.login_save_title, prompt.originHost),
                onDismiss = prompt::dismiss,
                confirmButton = {
                    TextButton(onClick = { prompt.answer(SaveLoginChoice.SAVE) }) {
                        Text(stringResource(if (prompt.isUpdate) R.string.login_update else R.string.login_save))
                    }
                },
                dismissButton = {
                    Row {
                        if (!prompt.isUpdate) {
                            TextButton(onClick = { prompt.answer(SaveLoginChoice.NEVER) }) { Text(stringResource(R.string.login_never)) }
                        }
                        TextButton(onClick = { prompt.answer(SaveLoginChoice.NOT_NOW) }) { Text(stringResource(R.string.login_not_now)) }
                    }
                },
            ) {
                Text(
                    if (prompt.username.isEmpty()) stringResource(R.string.login_no_username) else prompt.username,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.login_save_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

        is SitePrompt.SelectLogin ->
            PromptDialog(
                title = stringResource(R.string.login_select_title, prompt.originHost),
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = prompt::dismiss) { Text(stringResource(R.string.prompt_cancel)) } },
            ) {
                Column {
                    prompt.usernames.forEachIndexed { i, name ->
                        TextButton(onClick = { prompt.choose(i) }, modifier = Modifier.fillMaxWidth()) {
                            Text(name.ifEmpty { stringResource(R.string.login_no_username) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

        is SitePrompt.Repost ->
            PromptDialog(
                title = title,
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = prompt::resend) { Text(stringResource(R.string.prompt_resend)) } },
                dismissButton = { TextButton(onClick = prompt::cancel) { Text(stringResource(R.string.prompt_cancel)) } },
            ) { Text(stringResource(R.string.prompt_repost_message)) }

        is SitePrompt.DateTime -> DateTimePromptDialog(prompt, title)

        is SitePrompt.ColorPick -> ColorPromptDialog(prompt, title)

        is SitePrompt.FilePick -> FilePickDialog(prompt, title)

        is SitePrompt.SitePermission -> SitePermissionDialog(prompt)
    }
}

/**
 * Camera, microphone or location. The title is the requesting origin as the browser computed it (punycode, never page
 * text). Dismissing (back, tap outside, navigation) denies and remembers nothing; Allow and Deny both record, for this
 * session unless "Remember for this site" is ticked.
 */
@Composable
private fun SitePermissionDialog(prompt: SitePrompt.SitePermission) {
    var rememberChoice by remember(prompt) { mutableStateOf(false) }
    PromptDialog(
        title = stringResource(R.string.perm_prompt_title, prompt.origin),
        onDismiss = prompt::dismiss,
        confirmButton = {
            TextButton(onClick = { prompt.allow(prompt.offerRemember && rememberChoice) }) {
                Text(stringResource(R.string.perm_prompt_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { prompt.deny(prompt.offerRemember && rememberChoice) }) {
                Text(stringResource(R.string.perm_prompt_deny))
            }
        },
    ) {
        Column {
            for (kind in prompt.kinds) {
                Text(
                    stringResource(
                        when (kind) {
                            SitePermissionKind.CAMERA -> R.string.perm_prompt_camera
                            SitePermissionKind.MICROPHONE -> R.string.perm_prompt_microphone
                            SitePermissionKind.LOCATION -> R.string.perm_prompt_location
                        },
                    ),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            prompt.embedder?.let { embedder ->
                Text(
                    stringResource(R.string.perm_prompt_embedded, embedder),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Private sessions store no answer, so there is nothing to remember.
            if (prompt.offerRemember) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { rememberChoice = !rememberChoice }
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rememberChoice, onCheckedChange = null)
                    Text(stringResource(R.string.perm_prompt_remember), Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

/**
 * Shown after Android refused to show a permission dialog again. Not a site prompt: the page was already answered, so
 * this only offers the way out and holds nothing up.
 */
@Composable
fun PermissionSettingsDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    PromptDialog(
        title = stringResource(R.string.perm_blocked_title),
        onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.perm_open_settings)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.perm_not_now)) } },
    ) { Text(stringResource(R.string.perm_blocked_body)) }
}

/**
 * A file pick shows nothing while a system screen is open (BrowserScreen launches the document picker or camera). When
 * the page asked for a photo it can show Take picture / Choose file / Cancel first. After the user chose, the engine
 * copies the files and the prompt shows that progress, or the failure.
 */
@Composable
private fun FilePickDialog(prompt: SitePrompt.FilePick, title: String) {
    val stage by prompt.stage.collectAsStateWithLifecycle()
    when (stage) {
        FilePickStage.PICKING -> Unit

        FilePickStage.CHOOSING ->
            PromptDialog(
                title = title,
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = prompt::dismiss) { Text(stringResource(R.string.prompt_cancel)) } },
            ) {
                Column {
                    Text(stringResource(R.string.prompt_file_capture_message), Modifier.padding(bottom = 8.dp))
                    TextButton(onClick = prompt::takePicture) { Text(stringResource(R.string.prompt_file_take_picture)) }
                    TextButton(onClick = prompt::chooseDocuments) { Text(stringResource(R.string.prompt_file_choose)) }
                }
            }

        FilePickStage.PREPARING ->
            PromptDialog(
                title = title,
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = prompt::dismiss) { Text(stringResource(R.string.prompt_cancel)) } },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.prompt_file_preparing), Modifier.padding(start = 16.dp))
                }
            }

        FilePickStage.FAILED ->
            PromptDialog(
                title = title,
                onDismiss = prompt::dismiss,
                confirmButton = { TextButton(onClick = prompt::dismiss) { Text(stringResource(R.string.prompt_ok)) } },
            ) { Text(stringResource(R.string.prompt_file_failed)) }
    }
}

@Composable
private fun ChoiceDialog(prompt: SitePrompt.Choice, title: String) {
    var chosen by remember(prompt) {
        mutableStateOf(prompt.options.filter { it.selected && it.enabled && !it.isGroupHeader }.map { it.id }.toSet())
    }
    PromptDialog(
        title = title,
        onDismiss = prompt::dismiss,
        // A single choice answers on tap, so it only needs Cancel; a multiple choice needs an explicit OK.
        confirmButton = {
            if (prompt.multiple) {
                TextButton(onClick = { prompt.submit(prompt.options.filter { it.id in chosen && !it.isGroupHeader }.map { it.id }) }) {
                    Text(stringResource(R.string.prompt_ok))
                }
            } else {
                TextButton(onClick = prompt::dismiss) { Text(stringResource(R.string.prompt_cancel)) }
            }
        },
        dismissButton =
            if (prompt.multiple) {
                { TextButton(onClick = prompt::dismiss) { Text(stringResource(R.string.prompt_cancel)) } }
            } else {
                null
            },
    ) {
        Column {
            if (prompt.message.isNotEmpty()) Text(prompt.message, Modifier.padding(bottom = 8.dp))
            for (option in prompt.options) {
                if (option.isGroupHeader) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = (option.depth * 16).dp, top = 8.dp, bottom = 4.dp),
                    )
                    continue
                }
                val isChosen = if (prompt.multiple) option.id in chosen else option.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (option.enabled) 1f else 0.38f)
                        .clickable(enabled = option.enabled) {
                            if (prompt.multiple) {
                                chosen = if (option.id in chosen) chosen - option.id else chosen + option.id
                            } else {
                                prompt.submit(listOf(option.id))
                            }
                        }
                        .padding(start = (option.depth * 16).dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (prompt.multiple) {
                        Checkbox(checked = isChosen, onCheckedChange = null, enabled = option.enabled)
                    } else {
                        RadioButton(selected = isChosen, onClick = null, enabled = option.enabled)
                    }
                    Text(option.label, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

/**
 * Shared shell. Colours come from the dialog's own container (never from the page or the screen behind it), so text
 * can never end up dark-on-dark; long page messages scroll instead of pushing the buttons off screen.
 */
@Composable
private fun PromptDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { content() }
        },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
    )
}

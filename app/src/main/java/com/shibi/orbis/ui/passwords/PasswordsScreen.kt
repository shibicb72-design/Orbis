package com.shibi.orbis.ui.passwords

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shibi.orbis.R
import com.shibi.orbis.passwords.LoginManager
import com.shibi.orbis.passwords.SENSITIVE_CLIP_LABEL
import com.shibi.orbis.passwords.SavedLogin
import com.shibi.orbis.passwords.SensitiveClip
import com.shibi.orbis.ui.ObscuredTouchFilter
import com.shibi.orbis.ui.secondaryReadingWidth
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

internal const val PASSWORDS_UNLOCK_TAG = "passwords_unlock"
internal const val PASSWORDS_RESET_TAG = "passwords_reset"
internal const val PASSWORDS_RESET_CONFIRM_TAG = "passwords_reset_confirm"

/** Largest CSV accepted for import. */
private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

/** Logins whose site or username contains [query] (case-insensitive), sorted by site then username. */
internal fun filterLogins(logins: List<SavedLogin>, query: String): List<SavedLogin> {
    val q = query.trim().lowercase(Locale.ROOT)
    return logins.filter { q.isEmpty() || it.host.lowercase(Locale.ROOT).contains(q) || it.username.lowercase(Locale.ROOT).contains(q) }
        .sortedWith(compareBy<SavedLogin> { it.host.removePrefix("www.") }.thenBy { it.username })
}

/**
 * Settings > Passwords. The list, passwords and export are behind the screen lock (fingerprint or PIN/pattern/password
 * through Android's own prompt); unlocking lasts until the app leaves the screen (ON_STOP). Without a screen lock
 * nothing is shown. The caller protects the window (FLAG_SECURE) while this screen is up. When the saved logins stay
 * unreadable ([LoginManager.loadFailed]), the unlocked screen offers to reset them, after a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordsScreen(
    manager: LoginManager,
    offerSaveFlow: StateFlow<Boolean>,
    onSetOfferSave: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Test hook: start unlocked (Robolectric has no screen lock). */
    startUnlocked: Boolean = false,
    /** Clears a copied password from the clipboard after a minute; null copies without that (tests). */
    sensitiveClip: SensitiveClip? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val logins by manager.logins.collectAsStateWithLifecycle()
    val never by manager.neverSave.collectAsStateWithLifecycle()
    val offerSave by offerSaveFlow.collectAsStateWithLifecycle()
    val loadFailed by manager.loadFailed.collectAsStateWithLifecycle()
    var unlocked by remember { mutableStateOf(startUnlocked) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedLogin?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    // Leaving the app (Home, Recents, another app) locks the list again: a phone handed over later must ask first.
    // Every open menu and confirmation goes with it, so none can act on the relocked screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    unlocked = false
                    menu = false
                    confirmExport = false
                    deleting = null
                    confirmReset = false
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // F-15: no tap that comes through another app's window drawn on top reaches this screen (Android 10 and 11).
    ObscuredTouchFilter()
    val deviceSecure = remember { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val resolver = context.contentResolver
                val text = readPickedText(open = { resolver.openInputStream(uri) }, cap = MAX_IMPORT_BYTES)
                if (text == null) {
                    Toast.makeText(context, R.string.passwords_import_failed, Toast.LENGTH_SHORT).show()
                } else {
                    val r = manager.importCsv(text)
                    Toast.makeText(context, resources.getString(R.string.passwords_imported, r.added, r.skipped), Toast.LENGTH_LONG).show()
                }
            }
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val csv = manager.exportCsv()
            scope.launch {
                val resolver = context.contentResolver
                val ok = writePickedBytes(open = { resolver.openOutputStream(uri, "wt") }, bytes = csv.toByteArray(Charsets.UTF_8))
                Toast.makeText(context, if (ok) R.string.passwords_exported else R.string.passwords_export_failed, Toast.LENGTH_SHORT).show()
            }
        }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.passwords_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        // Behind the unlock like Export: whoever holds an unlocked phone must not plant logins.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.passwords_import)) },
                            enabled = unlocked,
                            onClick = {
                                menu = false
                                runCatching { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.passwords_export)) },
                            enabled = unlocked && logins.isNotEmpty(),
                            onClick = {
                                menu = false
                                confirmExport = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().secondaryReadingWidth()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.passwords_offer_save)) },
                supportingContent = { Text(stringResource(R.string.passwords_offer_save_summary)) },
                trailingContent = { Switch(checked = offerSave, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = offerSave, role = Role.Switch, onValueChange = onSetOfferSave),
            )
            HorizontalDivider()
            when {
                !unlocked && !deviceSecure ->
                    Text(stringResource(R.string.passwords_no_lock), modifier = Modifier.padding(16.dp))

                !unlocked ->
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.passwords_locked))
                        Button(
                            onClick = {
                                authenticate(
                                    context,
                                    resources.getString(R.string.passwords_auth_title),
                                    resources.getString(R.string.passwords_auth_subtitle),
                                ) { unlocked = true }
                            },
                            modifier = Modifier.padding(top = 12.dp).testTag(PASSWORDS_UNLOCK_TAG),
                        ) { Text(stringResource(R.string.passwords_unlock)) }
                    }

                loadFailed ->
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.passwords_unreadable))
                        Button(
                            onClick = { confirmReset = true },
                            modifier = Modifier.padding(top = 12.dp).testTag(PASSWORDS_RESET_TAG),
                        ) { Text(stringResource(R.string.passwords_reset)) }
                    }

                else -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.passwords_search_hint)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    val shown = remember(logins, query) { filterLogins(logins, query) }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        if (logins.isEmpty()) {
                            item { Text(stringResource(R.string.passwords_empty), modifier = Modifier.padding(16.dp)) }
                        }
                        items(shown, key = { it.guid }) { login -> LoginRow(login, sensitiveClip, onDelete = { deleting = login }) }
                        if (never.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.passwords_never_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                                )
                            }
                            items(never.sorted(), key = { "never:$it" }) { origin ->
                                ListItem(
                                    headlineContent = { Text(origin.substringAfter("://"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = {
                                        IconButton(onClick = { manager.setNeverSave(origin, false) }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.passwords_never_remove, origin.substringAfter("://")),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // The confirmations exist only while unlocked; each action checks again when it is tapped.
    if (unlocked && confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.passwords_export)) },
            text = { Text(stringResource(R.string.passwords_export_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    if (unlocked) runCatching { exportLauncher.launch("orbis-passwords.csv") }
                }) { Text(stringResource(R.string.passwords_export)) }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(stringResource(R.string.prompt_cancel)) } },
        )
    }
    if (unlocked && confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.passwords_reset)) },
            text = { Text(stringResource(R.string.passwords_reset_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        if (unlocked) manager.resetUnreadableStore()
                    },
                    modifier = Modifier.testTag(PASSWORDS_RESET_CONFIRM_TAG),
                ) { Text(stringResource(R.string.passwords_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.prompt_cancel)) } },
        )
    }
    deleting?.takeIf { unlocked }?.let { login ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.passwords_delete_confirm, login.host)) },
            confirmButton = {
                TextButton(onClick = {
                    if (unlocked) manager.delete(login.guid)
                    deleting = null
                }) { Text(stringResource(R.string.passwords_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.prompt_cancel)) } },
        )
    }
}

@Composable
private fun LoginRow(login: SavedLogin, sensitiveClip: SensitiveClip?, onDelete: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(login.host, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(login.username.ifEmpty { stringResource(R.string.login_no_username) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.clickable { open = !open },
        )
        if (open) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = login.password,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(onClick = { visible = !visible }) {
                        Text(stringResource(if (visible) R.string.passwords_hide else R.string.passwords_show))
                    }
                    TextButton(onClick = { copySensitive(context, login.username, sensitive = false, secrets = null) }) { Text(stringResource(R.string.passwords_copy_username)) }
                }
                Row {
                    TextButton(onClick = { copySensitive(context, login.password, sensitive = true, secrets = sensitiveClip) }) { Text(stringResource(R.string.passwords_copy_password)) }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.passwords_delete)) }
                }
            }
        }
        HorizontalDivider()
    }
}

/**
 * Copies to the clipboard. A secret ([sensitive]) is marked so the system does not preview it (Android 13+), and with
 * [secrets] it is cleared after a minute while it is still the clip ([SensitiveClip]).
 */
internal fun copySensitive(context: Context, text: String, sensitive: Boolean, secrets: SensitiveClip?) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    if (sensitive && secrets != null) {
        secrets.copy(clipboard, text)
        Toast.makeText(context, R.string.passwords_copied_clears, Toast.LENGTH_SHORT).show()
        return
    }
    val clip = ClipData.newPlainText(if (sensitive) SENSITIVE_CLIP_LABEL else "orbis", text)
    if (sensitive) {
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33); the same key string is honoured by Android 13+ only, harmless below.
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.passwords_copied, Toast.LENGTH_SHORT).show()
}

/**
 * What the unlock prompt accepts: on Android 11+ a strong (class 3) biometric or the screen lock (PIN, pattern,
 * password); a weak biometric alone is not enough. Null on Android 10, which uses setDeviceCredentialAllowed instead.
 */
@SuppressLint("InlinedApi") // Plain int constants, only used on API 30+.
internal fun allowedAuthenticators(sdkInt: Int): Int? =
    if (sdkInt >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        null
    }

/** Android's own biometric/screen-lock prompt (no extra library); [onSuccess] runs on the main thread. */
private fun authenticate(context: Context, title: String, subtitle: String, onSuccess: () -> Unit) {
    val activity = context.findActivity() ?: return
    val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
    val allowed = allowedAuthenticators(Build.VERSION.SDK_INT)
    if (allowed != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(allowed)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    val failed = runCatching {
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
    }.isFailure
    if (failed) Toast.makeText(context, R.string.passwords_auth_unavailable, Toast.LENGTH_LONG).show()
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

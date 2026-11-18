package com.shibi.orbis.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shibi.orbis.R
import com.shibi.orbis.backup.BackupError
import com.shibi.orbis.backup.BackupFormat
import com.shibi.orbis.backup.BackupNotReadyException
import com.shibi.orbis.backup.BackupOptions
import com.shibi.orbis.backup.BackupPasswordVault
import com.shibi.orbis.backup.BackupRefusedException
import com.shibi.orbis.backup.BiometricCipherPrompt
import com.shibi.orbis.backup.FileBackupPasswordVault
import com.shibi.orbis.backup.VaultCipher
import com.shibi.orbis.backup.hasStrongBiometric
import com.shibi.orbis.backup.systemCipherPrompt
import com.shibi.orbis.backup.BackupSealError
import com.shibi.orbis.backup.BackupSection
import com.shibi.orbis.backup.RestoreResult
import com.shibi.orbis.backup.SectionSkip
import com.shibi.orbis.engine.ProviderDeadline
import com.shibi.orbis.engine.providerCall
import com.shibi.orbis.ui.OrbisTopAppBar
import com.shibi.orbis.ui.authenticate
import com.shibi.orbis.ui.isScreenLockSet
import com.shibi.orbis.ui.passwords.PICKED_OPEN_TIMEOUT_MS
import com.shibi.orbis.ui.passwords.PICKED_TRANSFER_TIMEOUT_MS
import com.shibi.orbis.ui.passwords.writePickedBytes
import com.shibi.orbis.ui.secondaryReadingWidth
import java.text.Normalizer
import java.time.LocalDate
import javax.crypto.Cipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen needs from the backup code. The app passes `container.backup` and `container.addons`; tests pass fakes. */
class BackupActions(
    /** Makes the backup file. Zeroes the password it is given. May throw [BackupRefusedException] or [BackupNotReadyException]. */
    val create: suspend (CharArray, BackupOptions) -> ByteArray,
    /** Restores the file with the password (zeroed). */
    val restore: suspend (ByteArray, CharArray) -> RestoreResult,
    /** Asks the add-on manager to re-read what is installed and waits (a little) for it, so the backup lists add-ons. */
    val refreshAddons: suspend () -> Unit = {},
)

/** What reading a picked backup file gave. */
sealed interface BackupFileRead {
    class Bytes(val bytes: ByteArray) : BackupFileRead

    data object TooLarge : BackupFileRead

    data object Unreadable : BackupFileRead
}

/** Reads and writes the file the user picked. */
interface BackupFiles {
    suspend fun write(uri: Uri, bytes: ByteArray): Boolean

    suspend fun read(uri: Uri): BackupFileRead
}

/** The system file picker, as two calls: the callback gets the picked document, or null when the user backed out. */
interface BackupPickers {
    fun create(fileName: String, onResult: (Uri?) -> Unit)

    fun open(onResult: (Uri?) -> Unit)
}

/** [BackupFiles] over a content resolver. Every call into the provider has a deadline ([providerCall]); a file is at most 64 MB. */
class ContentBackupFiles(private val resolver: ContentResolver) : BackupFiles {
    override suspend fun write(uri: Uri, bytes: ByteArray): Boolean =
        writePickedBytes(open = { resolver.openOutputStream(uri, "wt") }, bytes = bytes)

    override suspend fun read(uri: Uri): BackupFileRead {
        val pool = ProviderDeadline.picked
        val input = providerCall(PICKED_OPEN_TIMEOUT_MS, pool = pool) { resolver.openInputStream(uri) } ?: return BackupFileRead.Unreadable
        try {
            val got =
                providerCall(PICKED_TRANSFER_TIMEOUT_MS, pool = pool) { BackupFormat.readLimited(input) ?: TOO_LARGE }
                    ?: return BackupFileRead.Unreadable
            return if (got === TOO_LARGE) BackupFileRead.TooLarge else BackupFileRead.Bytes(got as ByteArray)
        } finally {
            providerCall(1_000L, pool = pool) { input.close() }
        }
    }

    private companion object {
        val TOO_LARGE = Any()
    }
}

/** What the screen says after Create or Restore: one line each, and whether to offer the Add-ons screen. */
class BackupOutcome(val lines: List<String>, val offerAddons: Boolean = false)

/** True when the two passwords match and have at least [BackupFormat.MIN_PASSWORD_CHARS] characters (code points, after NFC, as BackupCrypto counts). */
internal fun backupPasswordOk(password: String, repeat: String): Boolean {
    if (password != repeat) return false
    val nfc = Normalizer.normalize(password, Normalizer.Form.NFC)
    return Character.codePointCount(nfc, 0, nfc.length) >= BackupFormat.MIN_PASSWORD_CHARS
}

private fun restoreFailureText(resources: Resources, error: BackupError): String =
    resources.getString(
        when (error) {
            BackupError.NOT_A_BACKUP -> R.string.backup_not_a_backup
            BackupError.UNSUPPORTED -> R.string.backup_newer
            BackupError.TOO_LARGE -> R.string.backup_file_too_large
            BackupError.WRONG_PASSWORD_OR_DAMAGED -> R.string.backup_wrong_password
        },
    )

/** The result lines of a restore, built from the counts: a part whose section was not in the file is left out. */
internal fun restoreOutcome(resources: Resources, result: RestoreResult): BackupOutcome =
    when (result) {
        is RestoreResult.Failed -> BackupOutcome(listOf(restoreFailureText(resources, result.error)))
        is RestoreResult.Restored -> {
            val parts = ArrayList<String>()
            result.counts[BackupSection.BOOKMARKS]?.let { parts += resources.getQuantityString(R.plurals.backup_part_bookmarks, it.added, it.added) }
            result.counts[BackupSection.PASSWORDS]?.let { parts += resources.getQuantityString(R.plurals.backup_part_passwords, it.added, it.added) }
            result.counts[BackupSection.TABS]?.let { parts += resources.getQuantityString(R.plurals.backup_part_tabs, it.added, it.added) }
            result.counts[BackupSection.HISTORY]?.let { parts += resources.getQuantityString(R.plurals.backup_part_history, it.added, it.added) }
            if (BackupSection.SETTINGS in result.counts) parts += resources.getString(R.string.backup_part_settings)
            val lines = ArrayList<String>()
            lines +=
                if (parts.isEmpty()) resources.getString(R.string.backup_restored_nothing)
                else resources.getString(R.string.backup_restored, parts.joinToString(", "))
            if (result.failedSections.isNotEmpty()) {
                val names = result.failedSections.sortedBy { BackupSection.ALL.indexOf(it) }.joinToString(", ") { sectionName(resources, it) }
                lines += resources.getString(R.string.backup_failed_sections, names)
            }
            if (SectionSkip.NEWER in result.skippedSections.values) lines += resources.getString(R.string.backup_skipped_newer)
            val offer = result.addonsToOffer.isNotEmpty() || result.offerUblockOrigin
            if (offer) lines += resources.getString(R.string.backup_addons_note)
            BackupOutcome(lines, offerAddons = offer)
        }
    }

private fun sectionName(resources: Resources, section: String): String =
    when (section) {
        BackupSection.SETTINGS -> resources.getString(R.string.backup_name_settings)
        BackupSection.SITE_PERMISSIONS -> resources.getString(R.string.backup_name_site_permissions)
        BackupSection.BOOKMARKS -> resources.getString(R.string.backup_name_bookmarks)
        BackupSection.PASSWORDS -> resources.getString(R.string.backup_name_passwords)
        BackupSection.TABS -> resources.getString(R.string.backup_name_tabs)
        BackupSection.HISTORY -> resources.getString(R.string.backup_name_history)
        BackupSection.ADDONS -> resources.getString(R.string.backup_name_addons)
        else -> section
    }

/**
 * Settings > Backup and restore. A sensitive screen (the caller blocks screenshots while it is up). With a screen lock
 * set, nothing shows until the prompt is passed. Passwords live only in this screen's state: they are cleared when the
 * app goes to the background, which also locks the screen again (except while the system file picker is open, which also stops this activity) and when
 * the screen closes. Key derivation and the file transfer run off the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    actions: BackupActions,
    onOpenAddons: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    files: BackupFiles = ContentBackupFiles(LocalContext.current.contentResolver),
    /** Test hook: replaces the system file picker. */
    pickers: BackupPickers? = null,
    today: () -> LocalDate = LocalDate::now,
    /** Test hook: whether the phone has a screen lock. */
    screenLockSet: (Context) -> Boolean = ::isScreenLockSet,
    /** Test hook: start unlocked (Robolectric cannot pass the system prompt). */
    startUnlocked: Boolean = false,
    /** Test hook: the screen-lock prompt. */
    authenticator: (Context, String, String, () -> Unit) -> Unit = ::authenticate,
    /** The backup password kept behind the fingerprint on this phone. */
    vault: BackupPasswordVault = LocalContext.current.let { remember(it) { FileBackupPasswordVault.forApp(it) } },
    /** Test hook: whether a class 3 biometric is enrolled. */
    strongBiometric: (Context) -> Boolean = ::hasStrongBiometric,
    /** Test hook: the fingerprint prompt for a vault cipher. */
    cipherPrompt: BiometricCipherPrompt = systemCipherPrompt,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var lockSet by remember { mutableStateOf(screenLockSet(context)) }
    var unlocked by remember { mutableStateOf(startUnlocked) }
    var includeTabs by remember { mutableStateOf(true) }
    var includeHistory by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var showPasswords by remember { mutableStateOf(false) }
    var restoreFile by remember { mutableStateOf<ByteArray?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<BackupOutcome?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    // Fingerprint (feat08-5d). Shown only with a class 3 biometric enrolled.
    var biometric by remember { mutableStateOf(strongBiometric(context)) }
    var stored by remember { mutableStateOf(false) }
    // The password of the last successful typed operation, offered for storing; zeroed when dropped.
    var lastPassword by remember { mutableStateOf<CharArray?>(null) }
    // The stored password read for one Create while the file picker is open; zeroed when used or dropped.
    var vaultChars by remember { mutableStateOf<CharArray?>(null) }
    // The stored password did not open the chosen file (or the prompt was cancelled): type it instead.
    var restoreTyped by remember { mutableStateOf(false) }
    val useFingerprint = biometric && stored

    fun dropLastPassword() {
        lastPassword?.fill('\u0000')
        lastPassword = null
    }

    fun dropVaultChars() {
        vaultChars?.fill('\u0000')
        vaultChars = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    lockSet = screenLockSet(context)
                    biometric = strongBiometric(context)
                }
                if (event == Lifecycle.Event.ON_STOP && !pickerOpen) {
                    unlocked = false
                    password = ""
                    repeat = ""
                    restorePassword = ""
                    restoreFile = null
                    confirmRestore = false
                    showPasswords = false
                    restoreTyped = false
                    dropLastPassword()
                    dropVaultChars()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dropLastPassword()
            dropVaultChars()
        }
    }

    LaunchedEffect(unlocked) { if (unlocked) stored = withContext(Dispatchers.IO) { vault.hasPassword } }

    fun prompt(cipher: Cipher, onCipher: (Cipher) -> Unit, onNone: () -> Unit) =
        cipherPrompt.show(
            context,
            resources.getString(R.string.backup_auth_title),
            resources.getString(R.string.backup_auth_subtitle),
            resources.getString(R.string.backup_cancel),
            cipher,
        ) { c -> if (c != null) onCipher(c) else onNone() }

    /** Prompts for the fingerprint and hands the stored password to [use] (which zeroes it); [onNone] when there is none. */
    fun withStoredPassword(use: (CharArray) -> Unit, onNone: () -> Unit) {
        scope.launch {
            when (val c = withContext(Dispatchers.IO) { vault.cipherToRead() }) {
                VaultCipher.KeyInvalidated -> {
                    stored = false
                    outcome = BackupOutcome(listOf(resources.getString(R.string.backup_fingerprint_invalidated)))
                    onNone()
                }
                VaultCipher.Unavailable -> {
                    stored = withContext(Dispatchers.IO) { vault.hasPassword }
                    onNone()
                }
                is VaultCipher.Ready ->
                    prompt(c.cipher, { authed ->
                        scope.launch {
                            val chars = withContext(Dispatchers.IO) { vault.read(authed) }
                            if (chars != null) use(chars) else onNone()
                        }
                    }, onNone)
            }
        }
    }

    fun storeLastPassword() {
        scope.launch {
            val c = withContext(Dispatchers.IO) { vault.cipherToStore() }
            if (c !is VaultCipher.Ready) {
                outcome = BackupOutcome(listOf(resources.getString(R.string.backup_fingerprint_unavailable)))
                return@launch
            }
            prompt(c.cipher, { authed ->
                scope.launch {
                    val pw = lastPassword ?: return@launch
                    val ok = withContext(Dispatchers.IO) { runCatching { vault.store(pw, authed) }.isSuccess }
                    if (!ok) {
                        withContext(Dispatchers.IO) { vault.clear() }
                        outcome = BackupOutcome(listOf(resources.getString(R.string.backup_fingerprint_unavailable)))
                    }
                    stored = withContext(Dispatchers.IO) { vault.hasPassword }
                    dropLastPassword()
                }
            }, {})
        }
    }

    /** Keeps [typed] (a copy the caller no longer needs) for the switch when nothing is stored yet; otherwise zeroes it. */
    fun offerToStore(typed: CharArray) {
        dropLastPassword()
        if (biometric && !stored) lastPassword = typed else typed.fill('\u0000')
    }

    val unlock = {
        authenticator(context, resources.getString(R.string.backup_auth_title), resources.getString(R.string.backup_auth_subtitle)) { unlocked = true }
    }
    LaunchedEffect(Unit) { if (lockSet && !unlocked) unlock() }

    fun startCreate(uri: Uri) {
        val fromVault = vaultChars
        vaultChars = null
        val pw = password
        val options = BackupOptions(includeTabs = includeTabs, includeHistory = includeHistory)
        scope.launch {
            working = true
            outcome = null
            val chars = fromVault ?: pw.toCharArray()
            val typed = if (fromVault == null) chars.copyOf() else null
            outcome =
                try {
                    actions.refreshAddons()
                    val bytes = actions.create(chars, options)
                    val saved = files.write(uri, bytes)
                    if (saved && typed != null) offerToStore(typed.copyOf())
                    BackupOutcome(listOf(resources.getString(if (saved) R.string.backup_saved else R.string.backup_save_failed)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupRefusedException) {
                    val id = if (e.reason == BackupSealError.TOO_LARGE) R.string.backup_too_large_to_save else R.string.backup_save_failed
                    BackupOutcome(listOf(resources.getString(id)))
                } catch (e: BackupNotReadyException) {
                    BackupOutcome(listOf(resources.getString(R.string.backup_not_ready)))
                } catch (e: Exception) {
                    BackupOutcome(listOf(resources.getString(R.string.backup_save_failed)))
                } finally {
                    chars.fill('\u0000')
                    typed?.fill('\u0000')
                    working = false
                }
        }
    }

    fun startRead(uri: Uri) {
        scope.launch {
            working = true
            outcome = null
            when (val r = files.read(uri)) {
                is BackupFileRead.Bytes -> {
                    restoreFile = r.bytes
                    restorePassword = ""
                    restoreTyped = false
                }
                BackupFileRead.TooLarge -> outcome = BackupOutcome(listOf(resources.getString(R.string.backup_file_too_large)))
                BackupFileRead.Unreadable -> outcome = BackupOutcome(listOf(resources.getString(R.string.backup_file_unreadable)))
            }
            working = false
        }
    }

    /** Restores [file] with [chars] (zeroed). [fromVault]: the stored password, so a wrong one falls back to typing. */
    fun runRestore(file: ByteArray, chars: CharArray, fromVault: Boolean) {
        val typed = if (fromVault) null else chars.copyOf()
        scope.launch {
            working = true
            outcome = null
            try {
                val result = actions.restore(file, chars)
                outcome = restoreOutcome(resources, result)
                if (result is RestoreResult.Restored) {
                    restoreFile = null
                    restorePassword = ""
                    restoreTyped = false
                    if (typed != null) offerToStore(typed.copyOf())
                } else if (fromVault && (result as RestoreResult.Failed).error == BackupError.WRONG_PASSWORD_OR_DAMAGED) {
                    restoreTyped = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupNotReadyException) {
                outcome = BackupOutcome(listOf(resources.getString(R.string.backup_not_ready)))
            } catch (e: Exception) {
                outcome = BackupOutcome(listOf(resources.getString(R.string.backup_file_unreadable)))
            } finally {
                chars.fill('\u0000')
                typed?.fill('\u0000')
                working = false
            }
        }
    }

    fun startRestore() {
        val file = restoreFile ?: return
        if (useFingerprint && !restoreTyped) {
            // The stored password is tried first; if it is missing or does not open the file, the password field shows.
            withStoredPassword(use = { chars -> runRestore(file, chars, fromVault = true) }, onNone = { restoreTyped = true })
        } else {
            runRestore(file, restorePassword.toCharArray(), fromVault = false)
        }
    }

    val createLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)) { uri ->
            pickerOpen = false
            if (uri != null) startCreate(uri) else dropVaultChars()
        }
    val openLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pickerOpen = false
            if (uri != null) startRead(uri)
        }

    fun pickCreate() {
        val name = BackupFormat.suggestedFileName(today())
        if (pickers != null) {
            pickerOpen = true
            pickers.create(name) { uri ->
                pickerOpen = false
                if (uri != null) startCreate(uri) else dropVaultChars()
            }
        } else {
            pickerOpen = true
            if (runCatching { createLauncher.launch(name) }.isFailure) {
                pickerOpen = false
                dropVaultChars()
            }
        }
    }

    fun createWithFingerprint() {
        withStoredPassword(
            use = { chars ->
                dropVaultChars()
                vaultChars = chars
                pickCreate()
            },
            onNone = {},
        )
    }

    fun pickOpen() {
        if (pickers != null) {
            pickerOpen = true
            pickers.open { uri ->
                pickerOpen = false
                if (uri != null) startRead(uri)
            }
        } else {
            pickerOpen = true
            if (runCatching { openLauncher.launch(arrayOf("*/*")) }.isFailure) pickerOpen = false
        }
    }

    val passwordTransformation = if (showPasswords) VisualTransformation.None else PasswordVisualTransformation()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            OrbisTopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().secondaryReadingWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (!lockSet) {
                // Without a screen lock anyone holding the phone could write every saved password to a file.
                Text(stringResource(R.string.backup_no_lock))
                return@Column
            }
            if (!unlocked) {
                Text(stringResource(R.string.backup_locked))
                Button(onClick = { unlock() }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.backup_unlock)) }
                return@Column
            }

            Text(stringResource(R.string.backup_create_heading), style = MaterialTheme.typography.titleMedium)
            TickRow(stringResource(R.string.backup_include_tabs), includeTabs, !working) { includeTabs = it }
            TickRow(stringResource(R.string.backup_include_history), includeHistory, !working) { includeHistory = it }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                enabled = !working,
                label = { Text(stringResource(R.string.backup_password)) },
                visualTransformation = passwordTransformation,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = repeat,
                onValueChange = { repeat = it },
                singleLine = true,
                enabled = !working,
                label = { Text(stringResource(R.string.backup_password_repeat)) },
                visualTransformation = passwordTransformation,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showPasswords = !showPasswords }) {
                    Text(stringResource(if (showPasswords) R.string.backup_hide_passwords else R.string.backup_show_passwords))
                }
                if (useFingerprint) {
                    OutlinedButton(onClick = { createWithFingerprint() }, enabled = !working, modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.backup_use_fingerprint))
                    }
                }
            }
            Text(stringResource(R.string.backup_password_rule), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { pickCreate() },
                enabled = !working && backupPasswordOk(password, repeat),
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(stringResource(R.string.backup_save)) }
            Text(stringResource(R.string.backup_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text(stringResource(R.string.backup_restore_heading), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { pickOpen() }, enabled = !working, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.backup_choose_file))
            }
            if (restoreFile != null) {
                Text(stringResource(R.string.backup_file_chosen), modifier = Modifier.padding(top = 8.dp))
                val typeIt = !useFingerprint || restoreTyped
                if (typeIt) {
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        singleLine = true,
                        enabled = !working,
                        label = { Text(stringResource(R.string.backup_password)) },
                        visualTransformation = passwordTransformation,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Button(
                    onClick = { confirmRestore = true },
                    enabled = !working && (!typeIt || restorePassword.isNotEmpty()),
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(stringResource(R.string.backup_restore)) }
            }

            if (working) Text(stringResource(R.string.backup_working), modifier = Modifier.padding(top = 16.dp))
            outcome?.let { o ->
                Column(Modifier.padding(top = 16.dp)) {
                    for (line in o.lines) Text(line)
                    if (o.offerAddons) Button(onClick = onOpenAddons, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.backup_open_addons)) }
                }
            }

            if (biometric && (stored || lastPassword != null)) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                if (stored) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { vault.clear() }
                                stored = withContext(Dispatchers.IO) { vault.hasPassword }
                                restoreTyped = false
                            }
                        },
                        enabled = !working,
                    ) { Text(stringResource(R.string.backup_forget_password)) }
                } else {
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(value = false, enabled = !working, role = Role.Switch) { on -> if (on) storeLastPassword() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.backup_fingerprint_switch), modifier = Modifier.weight(1f))
                        Switch(checked = false, onCheckedChange = null, enabled = !working)
                    }
                }
                Text(stringResource(R.string.backup_fingerprint_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            text = { Text(stringResource(R.string.backup_confirm_restore)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = false
                        startRestore()
                    },
                ) { Text(stringResource(R.string.backup_restore)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

@Composable
private fun TickRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

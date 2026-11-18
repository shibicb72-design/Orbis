package com.shibi.orbis.backup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.shibi.orbis.storage.AtomicTextFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A cipher for one fingerprint prompt, or why there is none. */
sealed interface VaultCipher {
    class Ready(val cipher: Cipher) : VaultCipher

    /** The key stopped working (new fingerprint enrolled, screen lock removed). The stored password has been deleted. */
    data object KeyInvalidated : VaultCipher

    /** Nothing stored, or the Keystore refused for another reason. */
    data object Unavailable : VaultCipher
}

/**
 * The backup password, kept on this phone only and sealed with a key that needs a strong biometric for every use. A
 * cipher from [cipherToStore] or [cipherToRead] must pass the fingerprint prompt before [store] or [read] can use it.
 * Blocking calls: run them off the main thread. The password is never logged.
 */
interface BackupPasswordVault {
    val hasPassword: Boolean

    /** Makes a fresh key (replacing any old one) and a cipher to seal with it. */
    fun cipherToStore(): VaultCipher

    /** A cipher to open what is stored. On [VaultCipher.KeyInvalidated] the stored file is already gone. */
    fun cipherToRead(): VaultCipher

    /** Seals [password] with an authenticated cipher from [cipherToStore]. Does not zero [password]. */
    fun store(password: CharArray, cipher: Cipher)

    /** The stored password (the caller zeroes it), or null when nothing usable is stored. */
    fun read(cipher: Cipher): CharArray?

    /** Deletes the stored password and the key. Never throws. */
    fun clear()
}

/** Where the vault key comes from. The app uses [KeystoreVaultKeys]; tests use a software key. */
interface VaultKeys {
    /** Deletes any old key and makes a new one. */
    fun newKey(): SecretKey

    /** The key, or null when there is none. May throw [KeyPermanentlyInvalidatedException]. */
    fun existingKey(): SecretKey?

    /** Never throws. */
    fun delete()
}

/**
 * AES-256-GCM in the Android Keystore, alias `orbis_backup_pw_v1`. Every use needs a class 3 biometric (no PIN or
 * pattern), and enrolling a new fingerprint invalidates the key.
 */
class KeystoreVaultKeys(private val alias: String = "orbis_backup_pw_v1") : VaultKeys {
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun newKey(): SecretKey {
        delete()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return gen.generateKey()
    }

    override fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    override fun delete() {
        runCatching {
            val ks = keyStore()
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }
}

/** One file: base64 of the 12-byte IV followed by the GCM-sealed UTF-8 password. */
class FileBackupPasswordVault(private val file: File, private val keys: VaultKeys) : BackupPasswordVault {
    private val text = AtomicTextFile(file)

    override val hasPassword: Boolean
        get() = file.isFile

    override fun cipherToStore(): VaultCipher =
        try {
            VaultCipher.Ready(Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keys.newKey()) })
        } catch (e: Exception) {
            VaultCipher.Unavailable
        }

    override fun cipherToRead(): VaultCipher {
        val sealed = sealedBytes() ?: return VaultCipher.Unavailable
        return try {
            // A missing key while the file is there means Android deleted it (the screen lock was removed).
            val key = keys.existingKey() ?: return invalidated()
            VaultCipher.Ready(Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)) })
        } catch (e: KeyPermanentlyInvalidatedException) {
            invalidated()
        } catch (e: Exception) {
            VaultCipher.Unavailable
        }
    }

    private fun invalidated(): VaultCipher {
        clear()
        return VaultCipher.KeyInvalidated
    }

    override fun store(password: CharArray, cipher: Cipher) {
        val plain = utf8(password)
        try {
            text.write(Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain)))
        } finally {
            plain.fill(0)
        }
    }

    override fun read(cipher: Cipher): CharArray? {
        val sealed = sealedBytes() ?: return null
        val plain =
            try {
                cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
            } catch (e: Exception) {
                return null
            }
        try {
            return chars(plain)
        } finally {
            plain.fill(0)
        }
    }

    override fun clear() {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, file.name + ".tmp").delete() }
        keys.delete()
    }

    private fun sealedBytes(): ByteArray? =
        runCatching { text.read()?.let { Base64.getDecoder().decode(it.trim()) } }.getOrNull()?.takeIf { it.size > IV_BYTES }

    companion object {
        const val FILE_NAME = "backup_password_v1.enc"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** The app's vault: in `noBackupFilesDir`, which Android never copies (the extraction rules exclude every domain as well). */
        fun forApp(context: Context): BackupPasswordVault =
            FileBackupPasswordVault(File(context.noBackupFilesDir, FILE_NAME), KeystoreVaultKeys())

        /** UTF-8 without passing through a String; the buffers are zeroed. */
        internal fun utf8(password: CharArray): ByteArray {
            val buffer: ByteBuffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
            val out = ByteArray(buffer.remaining())
            buffer.get(out)
            if (buffer.hasArray()) buffer.array().fill(0)
            return out
        }

        internal fun chars(utf8: ByteArray): CharArray {
            val buffer: CharBuffer = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(utf8))
            val out = CharArray(buffer.remaining())
            buffer.get(out)
            if (buffer.hasArray()) buffer.array().fill('\u0000')
            return out
        }
    }
}

/** True when the phone has a class 3 (strong) biometric enrolled. */
fun hasStrongBiometric(context: Context): Boolean =
    context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

/** The fingerprint prompt for one vault cipher. [onResult] gets the authenticated cipher, or null (cancelled, error, no prompt). */
fun interface BiometricCipherPrompt {
    fun show(context: Context, title: String, subtitle: String, cancel: String, cipher: Cipher, onResult: (Cipher?) -> Unit)
}

/** Android's prompt, strong biometric only (no screen-lock fallback: this key cannot be used with a PIN). Results on the main thread. */
val systemCipherPrompt =
    BiometricCipherPrompt { context, title, subtitle, cancel, cipher, onResult ->
        var done = false
        val finish = { c: Cipher? ->
            if (!done) {
                done = true
                onResult(c)
            }
        }
        val activity = context.findActivity()
        if (activity == null) {
            finish(null)
        } else {
            val executor = activity.mainExecutor
            runCatching {
                BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButton(cancel, executor) { _, _ -> finish(null) }
                    .build()
                    .authenticate(
                        BiometricPrompt.CryptoObject(cipher),
                        CancellationSignal(),
                        executor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                                finish(result.cryptoObject?.cipher ?: cipher)

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = finish(null)
                        },
                    )
            }.onFailure { finish(null) }
        }
    }

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

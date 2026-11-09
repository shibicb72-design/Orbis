package com.shibi.orbis.passwords

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.shibi.orbis.storage.AtomicTextFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts the login file. Tests use a stand-in; the app uses [KeystoreLoginCrypto]. */
interface LoginCrypto {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves it (hardware-backed where the phone has
 * it). Output: 12-byte IV followed by ciphertext+tag. The key does not require the screen lock for every use, so pages
 * can be filled; viewing passwords in Settings asks for the lock separately.
 */
class KeystoreLoginCrypto(private val alias: String = "orbis_logins_v1") : LoginCrypto {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "too short" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Where saved logins live. Blocking calls: run them off the main thread. */
interface LoginStore {
    fun load(): List<SavedLogin>

    fun save(items: List<SavedLogin>)
}

/**
 * `filesDir/logins/logins.enc`: the JSON list, encrypted, base64 in an atomically replaced file. A file that cannot be
 * decrypted or parsed (e.g. the key was lost on a restore to another phone) is set aside, never overwritten silently;
 * if it cannot be moved away, saving is refused.
 */
class FileLoginStore(private val file: AtomicTextFile, private val crypto: LoginCrypto) : LoginStore {
    @Volatile private var blocked = false

    override fun load(): List<SavedLogin> =
        try {
            val text = file.read()
            if (text.isNullOrBlank()) {
                emptyList()
            } else {
                LoginCodec.decode(String(crypto.decrypt(Base64.decode(text, Base64.NO_WRAP)), Charsets.UTF_8))
            }
        } catch (e: Exception) {
            if (!file.setAside()) blocked = true
            emptyList()
        }

    override fun save(items: List<SavedLogin>) {
        check(!blocked) { "unreadable login file could not be set aside" }
        val sealed = crypto.encrypt(LoginCodec.encode(items).toByteArray(Charsets.UTF_8))
        file.write(Base64.encodeToString(sealed, Base64.NO_WRAP))
        if (items.isEmpty()) file.deleteAside()
    }
}

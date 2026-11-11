package com.shibi.orbis.passwords

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.shibi.orbis.storage.AtomicTextFile
import java.io.IOException
import java.security.KeyStore
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONException

/** Encrypts and decrypts the login file. Tests use a stand-in; the app uses [KeystoreLoginCrypto]. */
interface LoginCrypto {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray

    /**
     * Forgets the key, so the next [encrypt] makes a fresh one. Everything sealed with the old key is lost. Only for the
     * user's explicit reset of an unreadable store.
     */
    fun reset()
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

    /** Deletes the key alias; [key] generates a new one on the next use. Throws if the Keystore refuses. */
    override fun reset() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
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

    /**
     * The user gave up on a store that stays unreadable: moves what is there out of the way and starts empty, so the
     * next [load] succeeds. Throws when that could not be done; saving stays refused then.
     */
    fun setAside()
}

/**
 * `filesDir/logins/logins.enc`: the JSON list, encrypted, base64 in an atomically replaced file. A file that cannot be
 * decrypted or parsed (e.g. the key was lost on a restore to another phone) is set aside, never overwritten silently;
 * if it cannot be moved away, saving is refused. Any other failure (Keystore busy or unavailable, an I/O error) may be
 * transient: the file is left in place, [load] rethrows, and saving is refused until a later [load] succeeds.
 */
class FileLoginStore(private val file: AtomicTextFile, private val crypto: LoginCrypto) : LoginStore {
    @Volatile private var blocked = false

    override fun load(): List<SavedLogin> =
        try {
            val text = file.read()
            val items =
                if (text.isNullOrBlank()) {
                    emptyList()
                } else {
                    LoginCodec.decode(String(crypto.decrypt(Base64.decode(text, Base64.NO_WRAP)), Charsets.UTF_8))
                }
            blocked = false
            items
        } catch (e: Exception) {
            blocked = true
            if (!isCorrupt(e)) throw e
            if (file.setAside()) blocked = false
            emptyList()
        }

    /**
     * Forgets the key first (if the Keystore refuses, nothing has changed and the user can try again), then sets the
     * file aside as `.corrupt`; the next [save] of an empty list deletes that copy. Saving is allowed again.
     */
    override fun setAside() {
        crypto.reset()
        if (!file.setAside()) throw IOException("login file still in place")
        blocked = false
    }

    override fun save(items: List<SavedLogin>) {
        check(!blocked) { "login file unreadable and still in place" }
        val sealed = crypto.encrypt(LoginCodec.encode(items).toByteArray(Charsets.UTF_8))
        file.write(Base64.encodeToString(sealed, Base64.NO_WRAP))
        if (items.isEmpty()) file.deleteAside()
    }

    private companion object {
        /**
         * The file itself is bad: wrong key or tampered ciphertext (bad GCM tag, [BadPaddingException] covers
         * `AEADBadTagException`), not Base64 or too short
         * ([IllegalArgumentException], also the `require` in [KeystoreLoginCrypto.decrypt]), or not our JSON.
         */
        fun isCorrupt(e: Exception): Boolean = e is BadPaddingException || e is IllegalArgumentException || e is JSONException
    }
}

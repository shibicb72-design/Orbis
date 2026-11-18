package com.shibi.orbis.backup

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fake for the Keystore: a software AES key, no biometric. [invalidated] makes the key throw as the Keystore does
 * after a new fingerprint is enrolled. The real [KeystoreVaultKeys] is not verified by unit test (feat08-5v, B9 to B11).
 */
class SoftwareVaultKeys : VaultKeys {
    var key: SecretKey? = null
    var invalidated = false

    override fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
        key = it
        invalidated = false
    }

    override fun existingKey(): SecretKey? {
        if (invalidated) throw KeyPermanentlyInvalidatedException()
        return key
    }

    override fun delete() {
        key = null
    }
}

/** Seals [password] as the screen does after the fingerprint prompt (which the fake skips). */
fun BackupPasswordVault.storeForTest(password: String) {
    val c = cipherToStore() as VaultCipher.Ready
    store(password.toCharArray(), c.cipher)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupPasswordVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private val keys = SoftwareVaultKeys()

    private fun file() = File(tmp.root, FileBackupPasswordVault.FILE_NAME)

    private fun vault() = FileBackupPasswordVault(file(), keys)

    private fun readBack(v: BackupPasswordVault): CharArray? = (v.cipherToRead() as? VaultCipher.Ready)?.let { v.read(it.cipher) }

    @Test(timeout = 30_000)
    fun storedPasswordReadsBackAndIsNotInTheFileAsText() {
        val v = vault()
        assertFalse(v.hasPassword)
        v.storeForTest("pässwörd 😀 123")
        assertTrue(v.hasPassword)
        assertArrayEquals("pässwörd 😀 123".toCharArray(), readBack(v))
        assertFalse(file().readText().contains("pässwörd"))
        // A second vault over the same file (the app restarted) reads it too.
        assertArrayEquals("pässwörd 😀 123".toCharArray(), readBack(FileBackupPasswordVault(file(), keys)))
    }

    @Test(timeout = 30_000)
    fun anInvalidatedKeyDeletesTheStoredFile() {
        val v = vault()
        v.storeForTest("1234567890")
        keys.invalidated = true
        assertEquals(VaultCipher.KeyInvalidated, v.cipherToRead())
        assertFalse(file().exists())
        assertFalse(v.hasPassword)
    }

    @Test(timeout = 30_000)
    fun aKeyAndroidDeletedCountsAsInvalidated() {
        val v = vault()
        v.storeForTest("1234567890")
        keys.key = null
        assertEquals(VaultCipher.KeyInvalidated, v.cipherToRead())
        assertFalse(file().exists())
    }

    @Test(timeout = 30_000)
    fun forgetLeavesNothing() {
        val v = vault()
        v.storeForTest("1234567890")
        v.clear()
        assertFalse(v.hasPassword)
        assertNull(keys.key)
        assertEquals(VaultCipher.Unavailable, v.cipherToRead())
    }

    @Test(timeout = 30_000)
    fun aDamagedFileReadsAsNothing() {
        val v = vault()
        v.storeForTest("1234567890")
        file().writeText("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertNull(readBack(v))
    }

    @Test(timeout = 30_000)
    fun utf8HelpersRoundTrip() {
        val bytes = FileBackupPasswordVault.utf8("aé😀".toCharArray())
        assertArrayEquals("aé😀".toByteArray(Charsets.UTF_8), bytes)
        assertArrayEquals("aé😀".toCharArray(), FileBackupPasswordVault.chars(bytes))
    }
}

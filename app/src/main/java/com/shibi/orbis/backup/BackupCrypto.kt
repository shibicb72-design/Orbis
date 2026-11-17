package com.shibi.orbis.backup

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seals and opens backup files ([BackupFormat]) with a key made from a password, so a file opens on any phone that has
 * the password. Without the password nobody can open the file, and a lost password cannot be recovered.
 *
 * - Key: PBKDF2-HMAC-SHA256 over the UTF-8 bytes of the NFC-normalised password, a fresh 16-byte random salt per file,
 *   [BackupFormat.DEFAULT_ITERATIONS] iterations, 256-bit key.
 * - Cipher: AES-256-GCM, 128-bit tag, a fresh 12-byte random nonce per file. Magic, header length and header are the
 *   additional authenticated data.
 *
 * PBKDF2 is computed here over `HmacSHA256` with the password bytes given explicitly, rather than through
 * `SecretKeyFactory("PBKDF2WithHmacSHA256")`: that API takes a `char[]` and leaves the char-to-byte conversion to the
 * security provider, which differs between the JVM and Android providers. A backup must derive the same key on every
 * phone, so the bytes are fixed here (UTF-8 of NFC) and the result is checked against independent vectors in the tests.
 *
 * Passwords: every call takes the password as a [CharArray] and overwrites it with zeros before it returns, whatever
 * the result. A caller that needs the password afterwards (feat08-5d) must pass a copy. The UTF-8 bytes and the derived
 * key are zeroed too. Two copies cannot be zeroed: the [String] that [Normalizer] returns when the password is not
 * already in NFC, and the key copies held inside the JCA `Mac` and `Cipher` objects; they are left to the garbage
 * collector. Nothing about the password or the content is logged.
 *
 * Key derivation takes about a second, so both calls are suspend functions that run on [dispatcher].
 */
class BackupCrypto internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val iterations: Int,
    private val randomBytes: (Int) -> ByteArray,
) {
    constructor(dispatcher: CoroutineDispatcher = Dispatchers.Default) :
        this(dispatcher, BackupFormat.DEFAULT_ITERATIONS, secureRandomBytes())

    /**
     * Encrypts [payload] (normally [BackupPayload.encode]) into a complete backup file. [appVersion] and [created]
     * go into the plain-text header. Zeroes [password]. Throws only a `GeneralSecurityException` on a platform without
     * HmacSHA256 or AES-GCM, which Android 12+ always has.
     */
    suspend fun seal(
        payload: ByteArray,
        password: CharArray,
        appVersion: String,
        created: LocalDate = LocalDate.now(),
    ): BackupSealResult =
        withContext(dispatcher) {
            try {
                sealBlocking(payload, password, appVersion, created)
            } finally {
                password.fill('\u0000')
            }
        }

    /**
     * Opens a backup file made by [seal]. Zeroes [password]. Never throws: every failure is a
     * [BackupOpenResult.Failed]. A wrong password and a changed or damaged file give the same
     * [BackupError.WRONG_PASSWORD_OR_DAMAGED], because AES-GCM cannot tell them apart.
     */
    suspend fun open(file: ByteArray, password: CharArray): BackupOpenResult =
        withContext(dispatcher) {
            try {
                openBlocking(file, password)
            } finally {
                password.fill('\u0000')
            }
        }

    private fun sealBlocking(
        payload: ByteArray,
        password: CharArray,
        appVersion: String,
        created: LocalDate,
    ): BackupSealResult {
        val passwordBytes = passwordBytes(password)
        try {
            if (passwordBytes.codePoints < BackupFormat.MIN_PASSWORD_CHARS) {
                return BackupSealResult.Refused(BackupSealError.PASSWORD_TOO_SHORT)
            }
            val header =
                BackupHeader(
                    version = BackupFormat.VERSION,
                    kdf = BackupFormat.KDF,
                    iterations = iterations,
                    salt = randomBytes(BackupFormat.SALT_BYTES),
                    nonce = randomBytes(BackupFormat.NONCE_BYTES),
                    created = created.toString(),
                    app = appVersion,
                )
            val aad = BackupFormat.prefix(BackupFormat.encodeHeader(header))
            if (aad.size.toLong() + payload.size + BackupFormat.TAG_BYTES > BackupFormat.MAX_FILE_BYTES) {
                return BackupSealResult.Refused(BackupSealError.TOO_LARGE)
            }
            val key = pbkdf2HmacSha256(passwordBytes.bytes, header.salt, header.iterations, BackupFormat.KEY_BYTES)
            val cipher =
                try {
                    gcm(Cipher.ENCRYPT_MODE, key, header.nonce)
                } finally {
                    key.fill(0)
                }
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(payload)
            return BackupSealResult.Sealed(aad + ciphertext)
        } finally {
            passwordBytes.bytes.fill(0)
        }
    }

    private fun openBlocking(file: ByteArray, password: CharArray): BackupOpenResult {
        val parsed =
            when (val p = BackupFormat.parse(file)) {
                is BackupFormat.Parsed.Ok -> p
                is BackupFormat.Parsed.Failed -> return BackupOpenResult.Failed(p.error)
            }
        val header = parsed.header
        val passwordBytes = passwordBytes(password)
        try {
            // An empty key is refused by SecretKeySpec; no file can be sealed with it.
            if (passwordBytes.bytes.isEmpty()) return BackupOpenResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED)
            return try {
                val key = pbkdf2HmacSha256(passwordBytes.bytes, header.salt, header.iterations, BackupFormat.KEY_BYTES)
                val cipher =
                    try {
                        gcm(Cipher.DECRYPT_MODE, key, header.nonce)
                    } finally {
                        key.fill(0)
                    }
                cipher.updateAAD(parsed.file, 0, parsed.prefixEnd)
                val payload = cipher.doFinal(parsed.file, parsed.prefixEnd, parsed.file.size - parsed.prefixEnd)
                BackupOpenResult.Opened(header, payload)
            } catch (_: GeneralSecurityException) {
                // AEADBadTagException in practice: wrong key or changed bytes, indistinguishable by design. (A platform
                // without HmacSHA256 or AES-GCM would land here too; Android 12+ has both.)
                BackupOpenResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED)
            }
        } finally {
            passwordBytes.bytes.fill(0)
        }
    }

    internal class PasswordBytes(val bytes: ByteArray, val codePoints: Int)

    internal companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC = "HmacSHA256"
        private const val HMAC_BYTES = 32

        fun secureRandomBytes(): (Int) -> ByteArray {
            val random = SecureRandom()
            return { n -> ByteArray(n).also(random::nextBytes) }
        }

        private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(BackupFormat.TAG_BITS, nonce))
            }

        /**
         * UTF-8 bytes of the NFC form of [password], and its length in code points. Does not change [password]. A lone
         * surrogate (cannot be typed) becomes the encoder's replacement byte, the same on every phone.
         */
        fun passwordBytes(password: CharArray): PasswordBytes {
            val chars = CharBuffer.wrap(password)
            val nfc: CharSequence =
                if (Normalizer.isNormalized(chars, Normalizer.Form.NFC)) chars
                else Normalizer.normalize(chars, Normalizer.Form.NFC)
            val codePoints = Character.codePointCount(nfc, 0, nfc.length)
            val encoder =
                Charsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val buffer: ByteBuffer =
                try {
                    encoder.encode(CharBuffer.wrap(nfc))
                } catch (e: CharacterCodingException) {
                    throw IllegalStateException("UTF-8 encoder with REPLACE refused input", e)
                }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            if (buffer.hasArray()) buffer.array().fill(0)
            return PasswordBytes(bytes, codePoints)
        }

        /** PBKDF2 (RFC 8018 section 5.2) with HMAC-SHA256. Zeroes its own working buffers. */
        fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
            require(iterations >= 1 && keyBytes >= 1)
            val mac = Mac.getInstance(HMAC)
            mac.init(SecretKeySpec(password, HMAC))
            val out = ByteArray(keyBytes)
            val u = ByteArray(HMAC_BYTES)
            val t = ByteArray(HMAC_BYTES)
            val blocks = (keyBytes + HMAC_BYTES - 1) / HMAC_BYTES
            try {
                for (block in 1..blocks) {
                    mac.update(salt)
                    mac.update(ByteBuffer.allocate(4).putInt(block).array())
                    mac.doFinal(u, 0)
                    u.copyInto(t)
                    for (i in 1 until iterations) {
                        mac.update(u)
                        mac.doFinal(u, 0)
                        for (j in 0 until HMAC_BYTES) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                    }
                    val offset = (block - 1) * HMAC_BYTES
                    t.copyInto(out, offset, 0, minOf(HMAC_BYTES, keyBytes - offset))
                }
            } finally {
                u.fill(0)
                t.fill(0)
            }
            return out
        }
    }
}

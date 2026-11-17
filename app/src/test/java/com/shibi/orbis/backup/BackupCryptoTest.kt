package com.shibi.orbis.backup

import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    /** The lowest iteration count a reader accepts, so most tests stay fast. One test uses the real 600,000. */
    private val fast = BackupCrypto(Dispatchers.Unconfined, BackupFormat.MIN_ITERATIONS, BackupCrypto.secureRandomBytes())

    private fun pw(s: String = "correct horse battery") = s.toCharArray()

    private val payload =
        BackupPayload(mapOf("bookmarks" to JSONObject().put("items", 3), "settings" to JSONObject().put("theme", "dark")))
            .encode()

    private fun seal(crypto: BackupCrypto = fast, password: String = "correct horse battery", data: ByteArray = payload) =
        runBlocking {
            val r = crypto.seal(data, pw(password), "0.7.5", LocalDate.of(2026, 11, 17))
            (r as BackupSealResult.Sealed).file
        }

    private fun open(file: ByteArray, password: String = "correct horse battery", crypto: BackupCrypto = fast) =
        runBlocking { crypto.open(file, pw(password)) }

    private fun header(file: ByteArray): JSONObject {
        val len = java.nio.ByteBuffer.wrap(file, 8, 4).int
        return JSONObject(String(file, 12, len, Charsets.UTF_8))
    }

    private fun headerEnd(file: ByteArray) = 12 + java.nio.ByteBuffer.wrap(file, 8, 4).int

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun sealThenOpenWithTheSamePasswordReturnsTheSamePayloadBytes() {
        val file = seal()
        val r = open(file) as BackupOpenResult.Opened
        assertArrayEquals(payload, r.payload)
        assertEquals("0.7.5", r.header.app)
        assertEquals("2026-11-17", r.header.created)
        val decoded = BackupPayload.decode(r.payload) as BackupPayload.DecodeResult.Ok
        assertEquals("dark", decoded.payload.sections.getValue("settings").getString("theme"))
    }

    /** seal must never write a header that open refuses: brackets and braces in the version name are dropped. */
    @Test
    fun aFileSealedWithBracketsInTheAppVersionOpensAgain() {
        runBlocking {
            for ((app, kept) in listOf("1.0 [debug] {x}" to "1.0 debug x", "v".repeat(500) to "v".repeat(64))) {
                val file = (fast.seal(payload, pw(), app, LocalDate.of(2026, 11, 17)) as BackupSealResult.Sealed).file
                val r = fast.open(file, pw()) as BackupOpenResult.Opened
                assertArrayEquals(payload, r.payload)
                assertEquals(kept, r.header.app)
            }
        }
    }

    @Test
    fun fullStrengthRoundTripUsesSixHundredThousandIterations() {
        val crypto = BackupCrypto(Dispatchers.Unconfined)
        val file = seal(crypto)
        assertEquals(600_000, header(file).getInt("iter"))
        assertArrayEquals(payload, (open(file, crypto = crypto) as BackupOpenResult.Opened).payload)
    }

    @Test
    fun aDifferentPasswordIsWrongPasswordOrDamaged() {
        val file = seal()
        assertEquals(BackupOpenResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), open(file, "correct horse batterY"))
        assertEquals(BackupOpenResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), open(file, ""))
    }

    /**
     * The bytes flipped in the header are in `created` and `app`, which do not feed the key: only the authenticated
     * data can catch them. (Mutation check: without `updateAAD` this test fails.)
     */
    @Test
    fun oneFlippedByteInTheHeaderIsWrongPasswordOrDamaged() {
        val file = seal()
        val text = String(file, Charsets.ISO_8859_1)
        for (needle in listOf("0.7.5", "2026-11-17")) {
            val at = text.indexOf(needle) + needle.length - 1
            assertTrue(at in 12 until headerEnd(file))
            val changed = file.copyOf()
            changed[at] = (changed[at] + 1).toByte() // '5' -> '6', '7' -> '8': still valid JSON
            assertEquals(needle, BackupOpenResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), open(changed))
        }
    }

    @Test
    fun oneFlippedByteInTheCiphertextIsWrongPasswordOrDamaged() {
        val file = seal()
        for (at in listOf(headerEnd(file), headerEnd(file) + 5, file.size - BackupFormat.TAG_BYTES - 1)) {
            val changed = file.copyOf()
            changed[at] = (changed[at].toInt() xor 0x01).toByte()
            assertEquals("byte $at", BackupOpenResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), open(changed))
        }
    }

    @Test
    fun oneFlippedByteInTheTagIsWrongPasswordOrDamaged() {
        val file = seal()
        for (at in listOf(file.size - BackupFormat.TAG_BYTES, file.size - 1)) {
            val changed = file.copyOf()
            changed[at] = (changed[at].toInt() xor 0x80).toByte()
            assertEquals("byte $at", BackupOpenResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), open(changed))
        }
    }

    @Test
    fun aTruncatedFileIsWrongPasswordOrDamaged() {
        val file = seal()
        assertEquals(BackupOpenResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), open(file.copyOf(file.size - 1)))
        assertEquals(BackupOpenResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), open(file.copyOf(headerEnd(file) + 3)))
    }

    /** Mutation check: with a fixed nonce this test fails. */
    @Test
    fun twoSealsOfTheSamePayloadAndPasswordDifferInSaltNonceAndCiphertext() {
        val a = seal()
        val b = seal()
        assertFalse(header(a).getString("salt") == header(b).getString("salt"))
        assertFalse(header(a).getString("nonce") == header(b).getString("nonce"))
        assertFalse(a.copyOfRange(headerEnd(a), a.size).contentEquals(b.copyOfRange(headerEnd(b), b.size)))
        assertEquals(16, Base64.getDecoder().decode(header(a).getString("salt")).size)
        assertEquals(12, Base64.getDecoder().decode(header(a).getString("nonce")).size)
    }

    @Test
    fun thePasswordArrayIsZeroedAfterEveryCall() {
        val file = seal()
        fun zeroed(p: CharArray) = p.all { it == '\u0000' }
        runBlocking {
            val p1 = pw()
            fast.seal(payload, p1, "0.7.5")
            assertTrue(zeroed(p1))
            val p2 = pw()
            fast.open(file, p2)
            assertTrue(zeroed(p2))
            val p3 = pw("wrong password here")
            fast.open(file, p3)
            assertTrue(zeroed(p3))
            val p4 = pw("short")
            fast.seal(payload, p4, "0.7.5")
            assertTrue(zeroed(p4))
            val p5 = pw()
            fast.open(byteArrayOf(1, 2, 3), p5)
            assertTrue(zeroed(p5))
        }
    }

    @Test
    fun aPasswordShorterThanTenCharactersIsRefused() {
        runBlocking {
            assertEquals(
                BackupSealResult.Refused(BackupSealError.PASSWORD_TOO_SHORT),
                fast.seal(payload, pw("123456789"), "0.7.5"),
            )
            // Nine letters, one of them typed as e + combining accent: nine characters after NFC.
            assertEquals(
                BackupSealResult.Refused(BackupSealError.PASSWORD_TOO_SHORT),
                fast.seal(payload, pw("caféabcd"), "0.7.5"),
            )
            assertTrue(fast.seal(payload, pw("1234567890"), "0.7.5") is BackupSealResult.Sealed)
            assertTrue(fast.seal(payload, pw("🔒".repeat(10)), "0.7.5") is BackupSealResult.Sealed)
        }
    }

    @Test
    fun aPayloadThatWouldPassTheFileLimitIsRefused() {
        runBlocking {
            assertEquals(
                BackupSealResult.Refused(BackupSealError.TOO_LARGE),
                fast.seal(ByteArray(BackupFormat.MAX_FILE_BYTES), pw(), "0.7.5"),
            )
        }
    }

    /** A password typed with a combining accent on one phone opens with the precomposed letter on another. */
    @Test
    fun thePasswordIsNfcNormalisedBeforeDerivation() {
        val file = seal(password = "Café-orbis-backup")
        assertArrayEquals(payload, (open(file, "Café-orbis-backup") as BackupOpenResult.Opened).payload)
    }

    /**
     * Known-answer vectors. Independent tool: Python 3.14.7 `hashlib.pbkdf2_hmac` (OpenSSL 3.5.7), run on 2026-11-17:
     * ```
     * hashlib.pbkdf2_hmac('sha256', b'passwd', b'salt', 1, 64)
     * hashlib.pbkdf2_hmac('sha256', unicodedata.normalize('NFC', 'Café-orbis-backup').encode('utf-8'),
     *                     bytes(range(16)), 600000, 32)
     * hashlib.pbkdf2_hmac('sha256', b'correct horse battery', bytes(range(16)), 100000, 32)
     * ```
     * The first is also RFC 7914 section 11's PBKDF2-HMAC-SHA256 vector.
     */
    @Test
    fun pbkdf2MatchesKnownAnswerVectors() {
        assertArrayEquals(
            hex(
                "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                    "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            ),
            BackupCrypto.pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64),
        )
        val salt = ByteArray(16) { it.toByte() }
        val nfc = BackupCrypto.passwordBytes("Café-orbis-backup".toCharArray())
        assertArrayEquals(hex("436166c3a92d6f726269732d6261636b7570"), nfc.bytes)
        assertArrayEquals(
            hex("65a3e849c364b1308fc0ef1bd57eb593a44a5c25bfb2aff3b8c4d2060b8c1ffa"),
            BackupCrypto.pbkdf2HmacSha256(nfc.bytes, salt, 600_000, 32),
        )
        assertArrayEquals(
            hex("c8d122ecdb9477cd48a6b1750c5985c0b8b05158d040d20517ccdb49865de436"),
            BackupCrypto.pbkdf2HmacSha256("correct horse battery".toByteArray(), salt, 100_000, 32),
        )
    }

    /** The JVM's own PBKDF2WithHmacSHA256 (which encodes the char[] as UTF-8) agrees with ours. */
    @Test
    fun pbkdf2AgreesWithTheJvmSecretKeyFactory() {
        val salt = ByteArray(16) { (it * 7).toByte() }
        for (p in listOf("correct horse battery", "Café üß 🔒 pass")) {
            val jvm =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(PBEKeySpec(p.toCharArray(), salt, 1000, 256))
                    .encoded
            assertArrayEquals(p, jvm, BackupCrypto.pbkdf2HmacSha256(p.toByteArray(Charsets.UTF_8), salt, 1000, 32))
        }
    }

    /**
     * With a fixed salt and nonce the file is fully determined: the header is exactly as specified, and a plain
     * AES-GCM decrypt with the independently computed key (Python vector above) and the prefix as AAD opens it.
     */
    @Test
    fun aFileWithAFixedSaltAndNonceHasTheSpecifiedLayout() {
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (0xA0 + it).toByte() }
        val fixed =
            BackupCrypto(Dispatchers.Unconfined, 100_000) { n ->
                when (n) {
                    16 -> salt.copyOf()
                    12 -> nonce.copyOf()
                    else -> error("unexpected $n")
                }
            }
        val file = seal(fixed)
        val expectedHeader =
            "{\"v\":1,\"kdf\":\"PBKDF2-HMAC-SHA256\",\"iter\":100000,\"salt\":\"AAECAwQFBgcICQoLDA0ODw==\"," +
                "\"nonce\":\"oKGio6Slpqeoqaqr\",\"created\":\"2026-11-17\",\"app\":\"0.7.5\"}"
        val headerBytes = expectedHeader.toByteArray(Charsets.UTF_8)
        assertEquals("ORBISBK1", String(file, 0, 8, Charsets.US_ASCII))
        assertEquals(headerBytes.size, java.nio.ByteBuffer.wrap(file, 8, 4).int)
        assertEquals(expectedHeader, String(file, 12, headerBytes.size, Charsets.UTF_8))
        assertEquals(12 + headerBytes.size + payload.size + 16, file.size)

        val key = hex("c8d122ecdb9477cd48a6b1750c5985c0b8b05158d040d20517ccdb49865de436")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(file, 0, 12 + headerBytes.size)
        assertArrayEquals(payload, c.doFinal(file, 12 + headerBytes.size, file.size - 12 - headerBytes.size))
    }

    /** Not an assertion: prints the time of one full-strength derivation on the test JVM for the report. */
    @Test
    fun timeOneDerivation() {
        val salt = ByteArray(16)
        BackupCrypto.pbkdf2HmacSha256("warm up".toByteArray(), salt, 20_000, 32)
        val start = System.nanoTime()
        BackupCrypto.pbkdf2HmacSha256("correct horse battery".toByteArray(), salt, BackupFormat.DEFAULT_ITERATIONS, 32)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("BACKUP_DERIVE_MS=$ms (PBKDF2-HMAC-SHA256, 600000 iterations, test JVM)")
    }
}

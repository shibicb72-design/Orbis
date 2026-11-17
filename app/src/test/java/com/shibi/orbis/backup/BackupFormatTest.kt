package com.shibi.orbis.backup

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val crypto = BackupCrypto(Dispatchers.Unconfined, BackupFormat.MIN_ITERATIONS, BackupCrypto.secureRandomBytes())

    private val goodHeader =
        """{"v":1,"kdf":"PBKDF2-HMAC-SHA256","iter":600000,"salt":"AAECAwQFBgcICQoLDA0ODw==","nonce":"oKGio6Slpqeoqaqr","created":"2026-11-17","app":"0.7.5"}"""

    /** A file with the given header and 32 bytes of stand-in ciphertext. */
    private fun file(header: String, magic: String = "ORBISBK1", body: ByteArray = ByteArray(32)): ByteArray {
        val h = header.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + h.size + body.size)
            .put(magic.toByteArray(Charsets.US_ASCII))
            .putInt(h.size)
            .put(h)
            .put(body)
            .array()
    }

    private fun open(file: ByteArray) = runBlocking { crypto.open(file, "correct horse battery".toCharArray()) }

    private fun failed(e: BackupError) = BackupOpenResult.Failed(e)

    @Test
    fun aWellFormedHeaderParses() {
        val p = BackupFormat.parse(file(goodHeader)) as BackupFormat.Parsed.Ok
        assertEquals(600_000, p.header.iterations)
        assertArrayEquals(ByteArray(16) { it.toByte() }, p.header.salt)
        assertEquals("0.7.5", p.header.app)
        // The stand-in ciphertext cannot decrypt, but the header was accepted.
        assertEquals(failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), open(file(goodHeader)))
    }

    @Test
    fun aWrongMagicATinyFileAndAnEmptyFileAreNotABackup() {
        assertEquals(failed(BackupError.NOT_A_BACKUP), open(file(goodHeader, magic = "ORBISBK2")))
        assertEquals(failed(BackupError.NOT_A_BACKUP), open(byteArrayOf(0x4F, 0x52, 0x42)))
        assertEquals(failed(BackupError.NOT_A_BACKUP), open(ByteArray(0)))
        assertEquals(failed(BackupError.NOT_A_BACKUP), open("ORBISBK1".toByteArray()))
    }

    @Test
    fun anUnknownVersionOrKdfIsUnsupported() {
        assertEquals(failed(BackupError.UNSUPPORTED), open(file(goodHeader.replace("\"v\":1", "\"v\":2"))))
        assertEquals(
            failed(BackupError.UNSUPPORTED),
            open(file(goodHeader.replace("PBKDF2-HMAC-SHA256", "argon2id"))),
        )
    }

    @Test
    fun iterationsOutsideTheAcceptedRangeAreRefused() {
        for (iter in listOf(1000, 99_999, 5_000_001)) {
            assertEquals("$iter", failed(BackupError.UNSUPPORTED), open(file(goodHeader.replace("600000", "$iter"))))
        }
        for (iter in listOf(100_000, 5_000_000)) {
            assertTrue("$iter", BackupFormat.parse(file(goodHeader.replace("600000", "$iter"))) is BackupFormat.Parsed.Ok)
        }
    }

    @Test
    fun aBadHeaderIsNotABackup() {
        val bad =
            listOf(
                "not json",
                goodHeader.replace("\"v\":1,", ""),
                goodHeader.replace("\"v\":1", "\"v\":\"1\""),
                goodHeader.replace("\"iter\":600000", "\"iter\":\"600000\""),
                goodHeader.replace("AAECAwQFBgcICQoLDA0ODw==", "AAECAwQFBgcICQoLDA0O"), // 15-byte salt
                goodHeader.replace("oKGio6Slpqeoqaqr", "!!!!"), // not base64
            )
        for (h in bad) assertEquals(h, failed(BackupError.NOT_A_BACKUP), open(file(h)))
    }

    @Test
    fun aHeaderOverFourKilobytesOrPastTheEndIsNotABackup() {
        val big = goodHeader.replace("\"app\":\"0.7.5\"", "\"app\":\"${"x".repeat(BackupFormat.MAX_HEADER_BYTES)}\"")
        assertEquals(failed(BackupError.NOT_A_BACKUP), open(file(big)))

        val pastEnd = file(goodHeader)
        ByteBuffer.wrap(pastEnd).putInt(8, pastEnd.size)
        assertEquals(failed(BackupError.NOT_A_BACKUP), open(pastEnd))

        val negative = file(goodHeader)
        ByteBuffer.wrap(negative).putInt(8, -1)
        assertEquals(failed(BackupError.NOT_A_BACKUP), open(negative))
    }

    @Test
    fun aFileOverSixtyFourMegabytesIsTooLarge() {
        val f = file(goodHeader, body = ByteArray(BackupFormat.MAX_FILE_BYTES))
        assertEquals(failed(BackupError.TOO_LARGE), open(f))
    }

    @Test
    fun readLimitedStopsPastSixtyFourMegabytes() {
        assertArrayEquals(byteArrayOf(1, 2, 3), BackupFormat.readLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        assertNull(BackupFormat.readLimited(zeros(BackupFormat.MAX_FILE_BYTES.toLong() + 1)))
        assertEquals(200_000, BackupFormat.readLimited(zeros(200_000))?.size)
    }

    private fun zeros(n: Long) =
        object : InputStream() {
            var left = n

            override fun read(): Int = if (left-- > 0) 0 else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val k = minOf(len.toLong(), left).toInt()
                b.fill(0, off, off + k)
                left -= k
                return k
            }
        }

    @Test
    fun theSuggestedFileNameCarriesTheDate() {
        assertEquals("orbis-backup-2026-11-17.orbisbackup", BackupFormat.suggestedFileName(LocalDate.of(2026, 11, 17)))
        assertEquals("application/octet-stream", BackupFormat.MIME_TYPE)
    }

    @Test
    fun aPayloadRoundTripsWithItsSections() {
        val p = BackupPayload(mapOf("a" to JSONObject().put("x", 1), "b" to JSONObject()))
        val bytes = p.encode()
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(1, o.getInt("v"))
        val back = (BackupPayload.decode(bytes) as BackupPayload.DecodeResult.Ok).payload
        assertEquals(setOf("a", "b"), back.sections.keys)
        assertEquals(1, back.sections.getValue("a").getInt("x"))
    }

    @Test
    fun aPayloadOfAnotherVersionOrShapeIsRefused() {
        fun decode(s: String) = BackupPayload.decode(s.toByteArray())
        assertEquals(BackupPayload.DecodeResult.Failed(BackupError.UNSUPPORTED), decode("""{"v":2,"sections":{}}"""))
        assertEquals(BackupPayload.DecodeResult.Failed(BackupError.NOT_A_BACKUP), decode("""{"v":1}"""))
        assertEquals(BackupPayload.DecodeResult.Failed(BackupError.NOT_A_BACKUP), decode("""{"v":1,"sections":{"a":3}}"""))
        assertEquals(BackupPayload.DecodeResult.Failed(BackupError.NOT_A_BACKUP), decode("garbage"))
    }
}

package com.shibi.orbis.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

/**
 * The Orbis backup file, version 1.
 *
 * Layout: 8 bytes magic `ORBISBK1`; 4 bytes big-endian header length; the header as UTF-8 JSON; then the AES-256-GCM
 * ciphertext (with its 16-byte tag) to the end of the file. The magic, the length and the header bytes are the
 * additional authenticated data, so any change to them makes the file fail to open.
 *
 * Header: `{"v":1,"kdf":"PBKDF2-HMAC-SHA256","iter":600000,"salt":"<base64>","nonce":"<base64>","created":"<date>",
 * "app":"<version name>"}`. It holds no user data.
 *
 * Plaintext: [BackupPayload], UTF-8 JSON `{"v":1,"sections":{...}}`.
 *
 * The encryption lives in [BackupCrypto]; this file holds the layout, the header and the result types.
 */
object BackupFormat {
    const val MAGIC = "ORBISBK1"
    const val VERSION = 1
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 5_000_000
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8
    const val MAX_HEADER_BYTES = 4 * 1024
    const val MAX_FILE_BYTES = 64 * 1024 * 1024

    /** Ruling by Shibi: at least 10 characters (Unicode code points, counted after NFC normalisation). */
    const val MIN_PASSWORD_CHARS = 10

    const val MIME_TYPE = "application/octet-stream"
    const val FILE_EXTENSION = "orbisbackup"

    internal val MAGIC_BYTES: ByteArray = MAGIC.toByteArray(Charsets.US_ASCII)
    internal const val LENGTH_BYTES = 4
    internal const val PREFIX_BYTES = 8 + LENGTH_BYTES

    /** The name offered in the file picker, for example `orbis-backup-2026-11-17.orbisbackup`. */
    fun suggestedFileName(date: LocalDate): String = "orbis-backup-$date.$FILE_EXTENSION"

    /**
     * Reads a whole backup file from [input], stopping as soon as it passes [MAX_FILE_BYTES]. Returns null for a file
     * that is too large; the caller then shows [BackupError.TOO_LARGE]. Does not close [input]. Throws [IOException]
     * from the stream (a file that cannot be read is the caller's I/O error, not a format error).
     */
    @Throws(IOException::class)
    fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_FILE_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * The header bytes, written by hand so the key order is fixed on every JSON library (Android's `org.json` keeps
     * insertion order, the JVM one does not). The bytes are authenticated as written; readers never re-encode them.
     */
    internal fun encodeHeader(header: BackupHeader): ByteArray {
        val enc = Base64.getEncoder()
        val json =
            "{\"v\":${header.version}," +
                "\"kdf\":${JSONObject.quote(header.kdf)}," +
                "\"iter\":${header.iterations}," +
                "\"salt\":\"${enc.encodeToString(header.salt)}\"," +
                "\"nonce\":\"${enc.encodeToString(header.nonce)}\"," +
                "\"created\":${JSONObject.quote(header.created)}," +
                "\"app\":${JSONObject.quote(header.app)}}"
        return json.toByteArray(Charsets.UTF_8)
    }

    /** Magic, length and header: the start of the file, and the additional authenticated data. */
    internal fun prefix(headerBytes: ByteArray): ByteArray =
        ByteBuffer.allocate(PREFIX_BYTES + headerBytes.size)
            .put(MAGIC_BYTES)
            .putInt(headerBytes.size)
            .put(headerBytes)
            .array()

    /** Splits a file into its authenticated prefix, its parsed header and its ciphertext. Never throws. */
    internal fun parse(file: ByteArray): Parsed {
        if (file.size < PREFIX_BYTES) return Parsed.Failed(BackupError.NOT_A_BACKUP)
        for (i in MAGIC_BYTES.indices) {
            if (file[i] != MAGIC_BYTES[i]) return Parsed.Failed(BackupError.NOT_A_BACKUP)
        }
        if (file.size > MAX_FILE_BYTES) return Parsed.Failed(BackupError.TOO_LARGE)
        val headerLength = ByteBuffer.wrap(file, MAGIC_BYTES.size, LENGTH_BYTES).int
        if (headerLength !in 2..MAX_HEADER_BYTES || headerLength > file.size - PREFIX_BYTES) {
            return Parsed.Failed(BackupError.NOT_A_BACKUP)
        }
        val prefixEnd = PREFIX_BYTES + headerLength
        val header =
            when (val h = decodeHeader(file.copyOfRange(PREFIX_BYTES, prefixEnd))) {
                is HeaderResult.Ok -> h.header
                is HeaderResult.Failed -> return Parsed.Failed(h.error)
            }
        // Shorter than a tag: cut off. GCM would refuse it anyway; say so without trying.
        if (file.size - prefixEnd < TAG_BYTES) return Parsed.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED)
        return Parsed.Ok(header, aad = file.copyOfRange(0, prefixEnd), ciphertext = file.copyOfRange(prefixEnd, file.size))
    }

    private fun decodeHeader(bytes: ByteArray): HeaderResult =
        try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val version = o.get("v") as? Int ?: return HeaderResult.Failed(BackupError.NOT_A_BACKUP)
            if (version != VERSION) return HeaderResult.Failed(BackupError.UNSUPPORTED)
            if (o.getString("kdf") != KDF) return HeaderResult.Failed(BackupError.UNSUPPORTED)
            val iterations = o.get("iter") as? Int ?: return HeaderResult.Failed(BackupError.NOT_A_BACKUP)
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return HeaderResult.Failed(BackupError.UNSUPPORTED)
            val dec = Base64.getDecoder()
            val salt = dec.decode(o.getString("salt"))
            val nonce = dec.decode(o.getString("nonce"))
            if (salt.size != SALT_BYTES || nonce.size != NONCE_BYTES) return HeaderResult.Failed(BackupError.NOT_A_BACKUP)
            HeaderResult.Ok(
                BackupHeader(
                    version = version,
                    kdf = KDF,
                    iterations = iterations,
                    salt = salt,
                    nonce = nonce,
                    created = o.optString("created", ""),
                    app = o.optString("app", ""),
                ),
            )
        } catch (_: JSONException) {
            HeaderResult.Failed(BackupError.NOT_A_BACKUP)
        } catch (_: IllegalArgumentException) {
            HeaderResult.Failed(BackupError.NOT_A_BACKUP) // bad base64
        }

    internal sealed interface Parsed {
        class Ok(val header: BackupHeader, val aad: ByteArray, val ciphertext: ByteArray) : Parsed

        data class Failed(val error: BackupError) : Parsed
    }

    private sealed interface HeaderResult {
        class Ok(val header: BackupHeader) : HeaderResult

        data class Failed(val error: BackupError) : HeaderResult
    }
}

/** The plain-text header of a backup file. [created] and [app] are informative only; nothing depends on them. */
class BackupHeader(
    val version: Int,
    val kdf: String,
    val iterations: Int,
    val salt: ByteArray,
    val nonce: ByteArray,
    /** ISO date the file was made, for example `2026-11-17`. */
    val created: String,
    /** Orbis version name that made the file. */
    val app: String,
)

/** Why a backup file could not be opened. Shown to the user by the backup screen (feat08-5c). */
enum class BackupError {
    /** Not an Orbis backup: wrong magic, too short, or a header that cannot be read. */
    NOT_A_BACKUP,

    /** An Orbis backup made by a newer Orbis (unknown format or payload version, unknown key derivation), or with
     * key-derivation settings outside what this version accepts. */
    UNSUPPORTED,

    /** Larger than [BackupFormat.MAX_FILE_BYTES]. */
    TOO_LARGE,

    /**
     * The password is wrong, or the file was changed or damaged after it was made. AES-GCM only reports that the
     * authentication tag does not match; it cannot tell a wrong key from a changed byte, so neither can we.
     */
    WRONG_PASSWORD_OR_DAMAGED,
}

/** Result of [BackupCrypto.open]. */
sealed interface BackupOpenResult {
    /** [payload] is the decrypted [BackupPayload] bytes; decode them with [BackupPayload.decode]. */
    class Opened(val header: BackupHeader, val payload: ByteArray) : BackupOpenResult

    data class Failed(val error: BackupError) : BackupOpenResult
}

/** Result of [BackupCrypto.seal]. */
sealed interface BackupSealResult {
    /** [file] is the complete backup file, to be written as is. */
    class Sealed(val file: ByteArray) : BackupSealResult

    data class Refused(val reason: BackupSealError) : BackupSealResult
}

enum class BackupSealError {
    /** Shorter than [BackupFormat.MIN_PASSWORD_CHARS]. */
    PASSWORD_TOO_SHORT,

    /** The finished file would pass [BackupFormat.MAX_FILE_BYTES], so no Orbis could open it. */
    TOO_LARGE,
}

/**
 * The plaintext of a backup: `{"v":1,"sections":{...}}`. Each section is an opaque JSON object here; feat08-5b defines
 * their names and contents. Unknown sections are kept on decode, so an older Orbis can skip what it does not know.
 */
class BackupPayload(val sections: Map<String, JSONObject>) {
    fun encode(): ByteArray {
        val s = JSONObject()
        for ((name, section) in sections) s.put(name, section)
        return JSONObject().put("v", VERSION).put("sections", s).toString().toByteArray(Charsets.UTF_8)
    }

    sealed interface DecodeResult {
        class Ok(val payload: BackupPayload) : DecodeResult

        data class Failed(val error: BackupError) : DecodeResult
    }

    companion object {
        const val VERSION = 1

        /**
         * Never throws. The bytes come from an authenticated file, so a payload that is not this JSON was made by a
         * different program or a newer Orbis: unknown `v` is [BackupError.UNSUPPORTED], anything else unreadable is
         * [BackupError.NOT_A_BACKUP].
         */
        fun decode(bytes: ByteArray): DecodeResult =
            try {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                val v = o.get("v") as? Int ?: return DecodeResult.Failed(BackupError.NOT_A_BACKUP)
                if (v != VERSION) return DecodeResult.Failed(BackupError.UNSUPPORTED)
                val s = o.getJSONObject("sections")
                val sections = LinkedHashMap<String, JSONObject>()
                for (name in s.keys()) sections[name] = s.getJSONObject(name)
                DecodeResult.Ok(BackupPayload(sections))
            } catch (_: JSONException) {
                DecodeResult.Failed(BackupError.NOT_A_BACKUP)
            }
    }
}

package com.shibi.orbis.cast

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Where [CastPins] keeps its records. Keys and values are ASCII (hex digits, `p2:` and `:`). */
internal interface CastPinStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)

    fun all(): Map<String, String>
}

/**
 * The private `cast_pins` preferences file. Clear browsing data does not touch it: a pin protects the user, and
 * dropping it would let the next impostor be trusted as a first connection.
 */
internal class PrefsCastPinStore(context: Context) : CastPinStore {
    private val prefs = context.applicationContext.getSharedPreferences(CAST_PINS_FILE, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
}

internal const val CAST_PINS_FILE = "cast_pins"

/** At most this many trusted (key, name) pairs are kept; the least recently used goes first. */
internal const val CAST_PIN_MAX_RECORDS = 64

/** How long a "Trust and cast" tap stays valid for the reconnect that follows it. */
internal const val CAST_TRUST_APPROVAL_MS = 120_000L

private const val RECORD_PREFIX = "p2:"

/**
 * The key [CastPins] stores for a friendly name: NFKC, lower case, controls and format characters (zero-width and
 * bidi marks) removed, whitespace runs collapsed, trimmed; then SHA-256 hex. An empty name counts as `chromecast`.
 */
internal fun castNameKey(name: String): String {
    val folded = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val out = StringBuilder(folded.length)
    var pendingSpace = false
    var i = 0
    while (i < folded.length) {
        val cp = folded.codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp)
        if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) continue
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            pendingSpace = out.isNotEmpty()
            continue
        }
        if (pendingSpace) out.append(' ')
        pendingSpace = false
        out.appendCodePoint(cp)
    }
    val normal = out.toString().ifEmpty { "chromecast" }
    return sha256Hex(normal.toByteArray(Charsets.UTF_8))
}

/**
 * Which Chromecast identity keys the user has trusted, and under which name. A pin is the pair (SHA-256 of the
 * deviceauth device-certificate key, [castNameKey] of the friendly name): the key is the part another device on the
 * Wi-Fi cannot fake (see CastDeviceAuth.kt), the name is what the user taps. The mDNS id is kept, hashed, only to
 * recognise "same id, different key". Nothing is pinned without a tap: a new pair returns [Verdict.NeedsConfirm], and
 * after [approve] the next connection that proves the same key under the same name writes it. The TLS leaf is not
 * pinned: receivers regenerate it every day or two. The store holds hashes only, never a name or id. Clear browsing
 * data does not touch it.
 *
 * Records: `p2:<keyFp>:<nameHash>` -> `<idHash>:<use>`, where `use` is a counter that grows with each use (not a
 * time: the file survives Clear browsing data and private-tab casts touch it too). Older releases stored `sha256(id)` -> `keyFp` (a key
 * of exactly 64 hex characters); such an entry is migrated the first time its device proves the same key.
 */
internal class CastPins(private val store: CastPinStore) {
    sealed interface Verdict {
        /** This key is trusted under this name. */
        data object MATCHED : Verdict

        /** The user approved this pair and the device proved it again. It is now trusted. */
        data object PINNED : Verdict

        /** Nothing was written. Ask the user, then [approve] the [ticket] and connect again. */
        data class NeedsConfirm(val reason: Reason, val ticket: Ticket) : Verdict
    }

    enum class Reason {
        /** Nothing stored matches this key, name or id. */
        FIRST_USE,

        /** A stored pin has this name or this id with another key. */
        KEY_CHANGED,

        /** This key is trusted under another name. */
        RENAMED,
    }

    /** One pending decision. Holds hashes only; [toString] prints none of them. */
    class Ticket internal constructor(
        internal val keyFp: String,
        internal val nameHash: String,
        internal val idHash: String,
        internal val createdMs: Long,
        internal val reason: Reason,
    ) {
        override fun toString(): String = "Ticket"
    }

    private class Approval(val ticket: Ticket, val approvedMs: Long)

    private class Record(val key: String, val keyFp: String, val nameHash: String, val idHash: String, val use: Long)

    private var approval: Approval? = null

    @Synchronized
    fun check(deviceId: String, name: String, spki: ByteArray, nowMs: Long): Verdict {
        val keyFp = sha256Hex(spki)
        val nameHash = castNameKey(name)
        val idHash = sha256Hex(deviceId.toByteArray(Charsets.UTF_8))
        val records = records()

        // 1. This key is trusted under this name.
        if (records.any { same(it.keyFp, keyFp) && same(it.nameHash, nameHash) }) {
            store.put(recordKey(keyFp, nameHash), "$idHash:${nextUse(records)}")
            return Verdict.MATCHED
        }

        // 2. The user just approved exactly this pair.
        val pending = approval
        if (pending != null &&
            same(pending.ticket.keyFp, keyFp) &&
            same(pending.ticket.nameHash, nameHash) &&
            nowMs - pending.approvedMs in 0 until CAST_TRUST_APPROVAL_MS
        ) {
            if (pending.ticket.reason == Reason.RENAMED) {
                records.filter { same(it.keyFp, keyFp) }.forEach { store.remove(it.key) }
            }
            store.put(recordKey(keyFp, nameHash), "$idHash:${nextUse(records)}")
            approval = null
            evict()
            return Verdict.PINNED
        }

        // 3. An entry from before names were pinned, for this id, holding this key: the device just proved it.
        val legacy = store.get(idHash)
        if (legacy != null && same(legacy, keyFp)) {
            store.put(recordKey(keyFp, nameHash), "$idHash:${nextUse(records)}")
            store.remove(idHash)
            evict()
            return Verdict.MATCHED
        }

        // 4. Ask.
        val reason =
            when {
                legacy != null -> Reason.KEY_CHANGED
                records.any { !same(it.keyFp, keyFp) && (same(it.nameHash, nameHash) || same(it.idHash, idHash)) } ->
                    Reason.KEY_CHANGED
                records.any { same(it.keyFp, keyFp) } -> Reason.RENAMED
                else -> Reason.FIRST_USE
            }
        return Verdict.NeedsConfirm(reason, Ticket(keyFp, nameHash, idHash, nowMs, reason))
    }

    /** The user tapped "Trust and cast". Kept in memory only, replacing any earlier approval. */
    @Synchronized
    fun approve(ticket: Ticket, nowMs: Long) {
        approval = Approval(ticket, nowMs)
    }

    @Synchronized
    fun cancelApproval() {
        approval = null
    }

    private fun records(): List<Record> =
        store.all().mapNotNull { (key, value) ->
            if (!key.startsWith(RECORD_PREFIX)) return@mapNotNull null
            val parts = key.removePrefix(RECORD_PREFIX).split(':')
            val fields = value.split(':')
            if (parts.size != 2 || fields.size != 2) return@mapNotNull null
            Record(key, parts[0], parts[1], fields[0], fields[1].toLongOrNull() ?: 0L)
        }

    /** One more than the highest use counter stored, so the record just used sorts last for eviction. */
    private fun nextUse(records: List<Record>): Long = (records.maxOfOrNull { it.use } ?: 0L) + 1

    private fun evict() {
        val records = records()
        if (records.size <= CAST_PIN_MAX_RECORDS) return
        records.sortedBy { it.use }.take(records.size - CAST_PIN_MAX_RECORDS).forEach { store.remove(it.key) }
    }

    private fun recordKey(keyFp: String, nameHash: String): String = "$RECORD_PREFIX$keyFp:$nameHash"

    private fun same(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

package com.shibi.orbis.engine

import java.io.File
import java.io.IOException
import java.net.IDN
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What a site can ask for. No engine types, so everything in this file is plain JVM and unit tested. */
enum class SitePermissionKind { CAMERA, MICROPHONE, LOCATION }

private const val MAX_ORIGIN_INPUT = 8_192
private const val MAX_HOST_LENGTH = 253
private const val MAX_LABEL_LENGTH = 63

/**
 * The origin (`scheme://host[:port]`) of an http(s) URL, or null. Pure and total: any input, however malformed, gives
 * null instead of throwing. Scheme and host are lower case, userinfo path query and fragment are dropped, a default
 * port is dropped and any other kept, an IPv6 host stays in brackets, and an internationalised host is converted to
 * punycode: this string is what the user sees in a permission dialog, so a look-alike Unicode host must never reach it.
 */
fun siteOriginOf(url: String?): String? {
    if (url == null || url.length > MAX_ORIGIN_INPUT) return null
    if (url.any { it.isWhitespace() || it.isISOControl() }) return null
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = url.substring(0, schemeEnd).lowercase(Locale.ROOT)
    val defaultPort =
        when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> return null
        }
    val authority = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
    // The last '@' wins, so `https://good.example@evil.example/` is evil.example.
    val hostPort = authority.substringAfterLast('@')
    val host: String
    val portText: String
    if (hostPort.startsWith("[")) {
        val end = hostPort.indexOf(']')
        if (end < 0) return null
        val inner = hostPort.substring(1, end)
        val validV6 = inner.contains(':') && inner.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!validV6) return null
        host = "[" + inner.lowercase(Locale.ROOT) + "]"
        val after = hostPort.substring(end + 1)
        portText =
            when {
                after.isEmpty() -> ""
                after.startsWith(":") -> after.substring(1)
                else -> return null
            }
    } else {
        val colon = hostPort.indexOf(':')
        val rawHost = if (colon < 0) hostPort else hostPort.substring(0, colon)
        portText = if (colon < 0) "" else hostPort.substring(colon + 1)
        host = asciiHost(rawHost) ?: return null
    }
    var port = defaultPort
    if (portText.isNotEmpty()) {
        if (portText.length > 5 || !portText.all { it in '0'..'9' }) return null
        port = portText.toInt()
        if (port !in 1..65_535) return null
    }
    return if (port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
}

private fun asciiHost(raw: String): String? {
    // One trailing dot (fully qualified form) names the same host.
    val trimmed = if (raw.endsWith(".")) raw.dropLast(1) else raw
    if (trimmed.isEmpty()) return null
    val ascii =
        try {
            IDN.toASCII(trimmed)
        } catch (e: IllegalArgumentException) {
            return null
        }
    val lower = ascii.lowercase(Locale.ROOT)
    if (lower.isEmpty() || lower.length > MAX_HOST_LENGTH) return null
    if (lower.startsWith(".") || lower.contains("..")) return null
    if (lower.split('.').any { it.length > MAX_LABEL_LENGTH }) return null
    if (!lower.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' }) return null
    return lower
}

/**
 * Identifies one decision. [origin] is the site that asked. [embedder] is the top-level origin when the requester is a
 * different origin (an iframe), otherwise null, so a decision made for a frame never answers for the site itself
 * or for the same frame embedded somewhere else.
 */
data class SitePermissionKey(val kind: SitePermissionKind, val origin: String, val embedder: String? = null)

/** A decision to persist. */
data class SitePermissionDecision(val key: SitePermissionKey, val allow: Boolean)

/** One row of the settings list. [session] true means it is kept in memory only and expires. */
data class SitePermissionEntry(val key: SitePermissionKey, val allow: Boolean, val session: Boolean)

/** Where remembered decisions live. Blocking IO: call from an IO dispatcher. */
interface SitePermissionStore {
    fun load(): List<SitePermissionDecision>

    @Throws(IOException::class)
    fun save(decisions: List<SitePermissionDecision>)
}

/**
 * Pure JSON codec. Stores origin, kind and allow only: never a URL or a path. Corrupt or unknown input decodes to
 * fewer entries and never throws. An origin that does not survive [siteOriginOf] unchanged is not ours and is skipped.
 */
object SitePermissionCodec {
    private const val VERSION = 1
    const val MAX_ENTRIES = 1_000

    fun encode(decisions: List<SitePermissionDecision>): String {
        val array = JSONArray()
        for (d in decisions) {
            val o = JSONObject()
            o.put("kind", d.key.kind.name)
            o.put("origin", d.key.origin)
            d.key.embedder?.let { o.put("embedder", it) }
            o.put("allow", d.allow)
            array.put(o)
        }
        return JSONObject().put("version", VERSION).put("entries", array).toString()
    }

    fun decode(text: String?): List<SitePermissionDecision> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONObject(text).optJSONArray("entries") ?: return emptyList()
            // A later duplicate replaces an earlier one, like record() does.
            val out = LinkedHashMap<SitePermissionKey, Boolean>()
            for (i in 0 until array.length()) {
                val d = decodeEntry(array.optJSONObject(i)) ?: continue
                out.remove(d.key)
                out[d.key] = d.allow
                if (out.size >= MAX_ENTRIES) break
            }
            out.map { SitePermissionDecision(it.key, it.value) }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun decodeEntry(o: JSONObject?): SitePermissionDecision? {
        if (o == null) return null
        return try {
            val kind = SitePermissionKind.entries.firstOrNull { it.name == o.getString("kind") } ?: return null
            val origin = o.getString("origin")
            if (siteOriginOf(origin) != origin) return null
            val embedder =
                if (o.has("embedder") && !o.isNull("embedder")) {
                    o.getString("embedder").also { if (siteOriginOf(it) != it) return null }
                } else {
                    null
                }
            SitePermissionDecision(SitePermissionKey(kind, origin, embedder), o.getBoolean("allow"))
        } catch (e: JSONException) {
            null
        }
    }
}

/** File-backed [SitePermissionStore]: temp file then atomic move, like the downloads index. */
class FileSitePermissionStore(private val file: File) : SitePermissionStore {
    /** Missing, unreadable or corrupt file yields an empty list. */
    override fun load(): List<SitePermissionDecision> =
        try {
            if (file.isFile) SitePermissionCodec.decode(file.readText(Charsets.UTF_8)) else emptyList()
        } catch (e: IOException) {
            emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    @Throws(IOException::class)
    override fun save(decisions: List<SitePermissionDecision>) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        tmp.writeText(SitePermissionCodec.encode(decisions), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * The user's answers, in two tiers: remembered (persisted) and this-session (memory only, gone after [SESSION_TTL_MS]
 * or at process end). A newer decision for a key replaces the older one in both tiers; [remove] clears both.
 * Main thread only (the scope is the main scope; [io] runs the file access).
 *
 * Nothing is written before [load] has finished, so a decision made while the file is still loading can never
 * overwrite the file's other entries with a partial list.
 */
class SitePermissionDecisions(
    private val store: SitePermissionStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    private class Temporary(val allow: Boolean, val expiresAt: Long)

    // Insertion order is age: a re-recorded key moves to the end, so the eldest is evicted at the cap.
    private val persisted = LinkedHashMap<SitePermissionKey, Boolean>()
    private val temporary = LinkedHashMap<SitePermissionKey, Temporary>()
    private val changedBeforeLoad = HashSet<SitePermissionKey>()
    private var loaded = false
    private var saveWanted = false
    private val saveLock = Any()

    @Volatile
    private var snapshot: List<SitePermissionDecision> = emptyList()

    private val _entries = MutableStateFlow<List<SitePermissionEntry>>(emptyList())

    /** Everything currently in force, sorted by origin. */
    val entries: StateFlow<List<SitePermissionEntry>> = _entries.asStateFlow()

    /** Reads the stored decisions. Decisions made meanwhile win over the file. */
    fun load() {
        scope.launch {
            val stored =
                try {
                    withContext(io) { store.load() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("site permissions load failed: ${e.javaClass.simpleName}")
                    emptyList()
                }
            for (d in stored) {
                if (d.key in changedBeforeLoad || d.key in temporary) continue
                persisted.remove(d.key)
                persisted[d.key] = d.allow
            }
            changedBeforeLoad.clear()
            trim()
            loaded = true
            publish()
            if (saveWanted) persistAsync()
            log("site permissions loaded")
        }
    }

    /** True allow, false deny, null no decision (or it expired). */
    fun lookup(key: SitePermissionKey): Boolean? {
        purgeExpired()
        return temporary[key]?.allow ?: persisted[key]
    }

    fun record(key: SitePermissionKey, allow: Boolean, remember: Boolean) {
        if (!loaded) markChangedBeforeLoad(key)
        purgeExpired()
        var persistedChanged = false
        if (remember) {
            temporary.remove(key)
            persisted.remove(key)
            persisted[key] = allow
            persistedChanged = true
        } else {
            persistedChanged = persisted.remove(key) != null
            temporary.remove(key)
            temporary[key] = Temporary(allow, now() + SESSION_TTL_MS)
        }
        trim()
        publish()
        if (persistedChanged) persistAsync()
    }

    fun remove(key: SitePermissionKey) {
        if (!loaded) markChangedBeforeLoad(key)
        temporary.remove(key)
        val persistedChanged = persisted.remove(key) != null
        publish()
        if (persistedChanged) persistAsync()
    }

    /** Drops session decisions that have expired and refreshes [entries]. */
    fun purgeExpired() {
        val t = now()
        val before = temporary.size
        temporary.entries.removeAll { it.value.expiresAt <= t }
        if (temporary.size != before) publish()
    }

    private fun markChangedBeforeLoad(key: SitePermissionKey) {
        changedBeforeLoad += key
        // The file may still hold an older answer for this key; the first save after loading must rewrite it.
        saveWanted = true
    }

    private fun trim() {
        while (persisted.size > SitePermissionCodec.MAX_ENTRIES) persisted.remove(persisted.keys.first())
        while (temporary.size > MAX_SESSION_ENTRIES) temporary.remove(temporary.keys.first())
    }

    private fun publish() {
        snapshot = persisted.map { SitePermissionDecision(it.key, it.value) }
        _entries.value =
            (persisted.map { SitePermissionEntry(it.key, it.value, session = false) } +
                temporary.map { SitePermissionEntry(it.key, it.value.allow, session = true) })
                .sortedWith(compareBy({ it.key.origin }, { it.key.embedder.orEmpty() }, { it.key.kind.ordinal }))
    }

    private fun persistAsync() {
        if (!loaded) {
            saveWanted = true
            return
        }
        // save() reads the snapshot when it runs, so racing saves always end with the latest list.
        scope.launch(io) { save() }
    }

    private fun save() {
        synchronized(saveLock) {
            try {
                store.save(snapshot)
            } catch (e: Exception) {
                log("site permissions save failed: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        /** Firefox's temporary-permission norm. */
        const val SESSION_TTL_MS = 60L * 60L * 1000L
        private const val MAX_SESSION_ENTRIES = 200
    }
}

/**
 * How one session uses the decision store. A private session never reads it (every request asks, so a remembered
 * answer from normal browsing does not show through) and never writes it (the answer applies to that request only, so no
 * private origin reaches site_permissions.json or the Settings list). Normal sessions pass straight through.
 */
class SessionPermissionPolicy(
    private val isPrivate: Boolean,
    private val lookup: (SitePermissionKey) -> Boolean?,
    private val record: (SitePermissionKey, Boolean, Boolean) -> Unit,
) {
    /** False when the dialog should not offer "remember": the answer is never stored. */
    val offerRemember: Boolean get() = !isPrivate

    fun lookup(key: SitePermissionKey): Boolean? = if (isPrivate) null else lookup.invoke(key)

    fun record(key: SitePermissionKey, allow: Boolean, remember: Boolean) {
        if (!isPrivate) record.invoke(key, allow, remember)
    }
}

enum class MediaPlan { GRANT, DENY, ASK }

/**
 * What to do with a camera/microphone request given the stored decisions. [lookup] is per requested kind.
 * Any stored deny: DENY. Everything allowed: GRANT. Otherwise ASK. Nothing requested: DENY.
 */
fun planMediaRequest(kinds: Set<SitePermissionKind>, lookup: (SitePermissionKind) -> Boolean?): MediaPlan {
    if (kinds.isEmpty()) return MediaPlan.DENY
    val answers = kinds.map(lookup)
    return when {
        answers.any { it == false } -> MediaPlan.DENY
        answers.all { it == true } -> MediaPlan.GRANT
        else -> MediaPlan.ASK
    }
}

/**
 * Pending answer callbacks per key. The first [join] for a key owns the dialog; later identical requests wait on it
 * and are answered together, so a page calling watchPosition twice never stacks dialogs. [answer] completes every
 * waiter exactly once and forgets the key. Main thread only.
 */
class PermissionPromptCoalescer<K> {
    private val pending = LinkedHashMap<K, MutableList<(Boolean) -> Unit>>()

    /** True if the caller is first and must show the dialog; false if it joined a pending one. */
    fun join(key: K, onAnswer: (Boolean) -> Unit): Boolean {
        val waiting = pending[key]
        if (waiting != null) {
            waiting += onAnswer
            return false
        }
        pending[key] = mutableListOf(onAnswer)
        return true
    }

    /** Answers everyone waiting on [key]. Later calls for the same pending set do nothing. */
    fun answer(key: K, allow: Boolean) {
        val waiting = pending.remove(key) ?: return
        for (callback in waiting) runCatching { callback(allow) }
    }

    fun isPending(key: K): Boolean = key in pending
}

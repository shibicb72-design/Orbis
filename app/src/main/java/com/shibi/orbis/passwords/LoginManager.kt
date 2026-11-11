package com.shibi.orbis.passwords

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sites (origins) the user said "Never" to: no save offer there. Not secret, kept in plain preferences. */
interface NeverSavePrefs {
    var neverSave: Set<String>
}

/** Result of [LoginManager.importCsv]. */
data class LoginImportResult(val added: Int, val skipped: Int)

/**
 * The saved logins. Main thread only. [load] reads the encrypted store; until it has succeeded, the engine is
 * offered nothing and changes are refused (a login form at the very first page of a cold start is filled on the next
 * load). Writes go to one writer coroutine in order, so an older list can never land last. No username, password or
 * site ever goes to the log.
 */
class LoginManager(
    private val store: LoginStore,
    private val never: NeverSavePrefs,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newGuid: () -> String = { "{" + UUID.randomUUID() + "}" },
) {
    private val _logins = MutableStateFlow<List<SavedLogin>>(emptyList())
    val logins: StateFlow<List<SavedLogin>> = _logins.asStateFlow()

    private val _neverSave = MutableStateFlow(never.neverSave)
    val neverSave: StateFlow<Set<String>> = _neverSave.asStateFlow()

    var loaded = false
        private set
    private var loading = false
    private val writes = Channel<List<SavedLogin>>(Channel.CONFLATED)
    private var writerStarted = false

    /**
     * Reads the store unless it is loaded or a read is running. If the read fails (e.g. a passing Keystore error), the
     * manager stays not loaded and a later call tries again; the app calls this on every start of the main screen.
     */
    fun load() {
        if (loaded || loading) return
        loading = true
        scope.launch {
            try {
                val stored = withContext(io) { store.load() }
                _logins.value = stored
                loaded = true
                log("logins loaded ${stored.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("logins load failed: ${e.javaClass.simpleName}")
            } finally {
                loading = false
            }
        }
    }

    private fun persist() {
        if (!writerStarted) {
            writerStarted = true
            scope.launch(io) {
                for (snap in writes) {
                    try {
                        store.save(snap)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("logins save failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        writes.trySend(_logins.value)
    }

    private fun update(list: List<SavedLogin>) {
        _logins.value = list
        persist()
    }

    /** Logins Gecko may fill on a page of [domain] (base domain); empty until loaded. */
    fun forDomain(domain: String): List<SavedLogin> = if (!loaded) emptyList() else _logins.value.filter { loginMatchesDomain(it.origin, domain) }

    /**
     * Saves what the user confirmed: an entry with a known [guid] is updated; otherwise an existing login for the same
     * origin and username gets the new password; otherwise a new login is added. Returns false when refused.
     */
    fun save(origin: String, username: String, password: String, formActionOrigin: String = "", httpRealm: String = "", guid: String? = null): Boolean {
        if (!loaded || password.isEmpty()) return false
        val o = loginOrigin(origin) ?: return false
        val t = now()
        val list = _logins.value
        val i =
            list.indexOfFirst { it.guid == guid }.takeIf { guid != null && it >= 0 }
                ?: list.indexOfFirst { it.origin == o && it.username == username && it.httpRealm == httpRealm }
        update(
            if (i >= 0) {
                list.toMutableList().also { it[i] = it[i].copy(username = username, password = password, timeLastUsed = t) }
            } else {
                list + SavedLogin(newGuid(), o, loginOrigin(formActionOrigin).orEmpty(), httpRealm, username, password, t, t, 0)
            },
        )
        log("login saved")
        return true
    }

    /** True when a save offer for [username] on [origin] would change an existing login (its password differs). */
    fun isUpdate(origin: String, username: String, password: String): Boolean {
        val o = loginOrigin(origin) ?: return false
        return _logins.value.any { it.origin == o && it.username == username && it.password != password }
    }

    fun markUsed(guid: String) {
        val list = _logins.value
        val i = list.indexOfFirst { it.guid == guid }
        if (i < 0) return
        update(list.toMutableList().also { it[i] = it[i].copy(timeLastUsed = now(), timesUsed = it[i].timesUsed + 1) })
    }

    fun delete(guid: String) {
        if (_logins.value.none { it.guid == guid }) return
        update(_logins.value.filterNot { it.guid == guid })
        log("login deleted")
    }

    /** Deletes every saved login (Clear browsing data). Refused before [load] finished, so nothing loads in over it. */
    fun clearAll() {
        check(loaded) { "logins not loaded" }
        if (_logins.value.isEmpty()) return
        update(emptyList())
        log("logins cleared")
    }

    // ---- never save ----

    fun isNeverSave(origin: String): Boolean = loginOrigin(origin)?.let { it in _neverSave.value } == true

    fun setNeverSave(origin: String, never: Boolean) {
        val o = loginOrigin(origin) ?: return
        val next = if (never) _neverSave.value + o else _neverSave.value - o
        this.never.neverSave = next
        _neverSave.value = next
    }

    // ---- import / export ----

    /** Adds the logins of a CSV export; one already saved for the same site and username is kept, not overwritten. */
    fun importCsv(text: String): LoginImportResult {
        if (!loaded) return LoginImportResult(0, 0)
        val parsed = LoginCsv.parse(text)
        val list = _logins.value.toMutableList()
        var added = 0
        var skipped = parsed.skipped
        val t = now()
        for (l in parsed.logins) {
            if (list.any { it.origin == l.origin && it.username == l.username && it.httpRealm == l.httpRealm }) {
                skipped++
                continue
            }
            list += SavedLogin(newGuid(), l.origin, l.formActionOrigin, l.httpRealm, l.username, l.password, t, 0L, 0)
            added++
        }
        if (added > 0) update(list)
        log("logins imported $added, skipped $skipped")
        return LoginImportResult(added, skipped)
    }

    fun exportCsv(): String = LoginCsv.export(_logins.value.sortedBy { it.origin })
}

package com.shibi.orbis.addons

import com.shibi.orbis.engine.AddonAccess
import com.shibi.orbis.engine.ExtensionDisabledReason
import com.shibi.orbis.engine.ExtensionHost
import com.shibi.orbis.engine.ExtensionInstallError
import com.shibi.orbis.engine.ExtensionInstallException
import com.shibi.orbis.engine.InstalledExtension
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class AddonFailure {
    NETWORK,
    SIGNATURE,
    INCOMPATIBLE,
    BLOCKED,
    CORRUPT,
    WRONG_EXTENSION,
    UNKNOWN,
    TIMEOUT,
}

sealed interface AddonState {
    data object NotInstalled : AddonState
    data object Installing : AddonState
    data class Installed(
        val version: String,
        val enabled: Boolean,
        val allowedInPrivate: Boolean,
        /** The add-on's own settings page (moz-extension://...), or null when it has none. */
        val optionsPageUrl: String?,
        /** An update is waiting that asks for more access than the user agreed to; it is not installed. */
        val updateBlocked: Boolean = false,
        /** The engine turned it off (blocklist or signature), not the user. It has no switch. */
        val disabledByBrowser: Boolean = false,
        /** Off for a reason that is neither the user nor the blocklist or signature. It has no switch either. */
        val disabledOther: Boolean = false,
    ) : AddonState
    data class Failed(val reason: AddonFailure) : AddonState
}

/**
 * Installs, lists, switches and removes the add-ons in [AddonCatalog], and nothing else: every call with an id outside
 * the catalogue is refused before the engine is asked. uBlock Origin and Orbis's built-in extensions are never in the
 * catalogue, so they are never touched here. One operation runs at a time (the engine allows one install in flight).
 * [scope] must outlive screens, so leaving a screen never cancels an install.
 */
class AddonManager(
    private val host: ExtensionHost,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val prefs: AddonPrefs,
    private val now: () -> Long,
    private val installTimeoutMillis: Long = DEFAULT_INSTALL_TIMEOUT_MILLIS,
    private val confirmLimitMillis: Long = DEFAULT_CONFIRM_LIMIT_MILLIS,
) {
    private val _states = MutableStateFlow<Map<String, AddonState>>(emptyMap())

    /** State per catalogue id. Empty until the first [refresh]; an id missing from the map is not known yet. */
    val states: StateFlow<Map<String, AddonState>> = _states.asStateFlow()

    private val opLock = Mutex()

    /** Reads what is installed from the engine. */
    fun refresh() {
        scope.launch { opLock.withLock { reload() } }
    }

    /**
     * Installs catalogue add-on [id]. [confirm] receives the access the engine reports and must return true for the
     * install to go ahead (the "Add" tap); false ends in [AddonState.NotInstalled]. The time the user takes to answer
     * does not count against the install timeout, but an answer that takes longer than [confirmLimitMillis] counts as
     * false. Returns false, and asks the engine nothing, when [id] is not in the
     * catalogue or the add-on is already installing or installed.
     */
    fun install(id: String, confirm: suspend (AddonAccess) -> Boolean): Boolean {
        val addon = AddonCatalog.find(id) ?: return refused("install")
        when (_states.value[id]) {
            AddonState.Installing, is AddonState.Installed -> return false
            else -> Unit
        }
        set(id, AddonState.Installing)
        scope.launch { opLock.withLock { runInstall(addon, confirm) } }
        return true
    }

    /**
     * Checks every installed catalogue add-on for an update, one after another, at most once per 24 hours each. Call it
     * well after launch: an update restarts the add-on. Nothing is asked of the engine while an install is queued or
     * running, and an add-on that is not installed is never asked about.
     */
    fun checkForUpdatesIfDue() {
        scope.launch {
            opLock.withLock { reload() }
            for (addon in AddonCatalog.entries) opLock.withLock { checkForUpdate(addon) }
        }
    }

    /** Returns false when [id] is not an installed catalogue add-on. */
    fun setEnabled(id: String, enabled: Boolean): Boolean =
        mutate(id, "setEnabled($enabled)") { host.setEnabled(id, enabled).toState() }

    /** Lets the add-on run in private tabs, or stops it there. Returns false when [id] is not an installed catalogue add-on. */
    fun setAllowedInPrivate(id: String, allowed: Boolean): Boolean =
        mutate(id, "setAllowedInPrivate($allowed)") { host.setAllowedInPrivateBrowsing(id, allowed).toState() }

    /** Returns false when [id] is not an installed catalogue add-on. */
    fun remove(id: String): Boolean =
        mutate(id, "remove") {
            host.uninstall(id)
            prefs.clear(id)
            AddonState.NotInstalled
        }

    private fun refused(what: String): Boolean {
        log("add-on $what refused: not in the list")
        return false
    }

    private fun mutate(id: String, what: String, block: suspend () -> AddonState): Boolean {
        val addon = AddonCatalog.find(id) ?: return refused(what)
        if (_states.value[id] !is AddonState.Installed) return false
        scope.launch {
            opLock.withLock {
                try {
                    set(addon.id, block())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("${addon.name} $what failed: ${e.javaClass.simpleName}")
                    reload()
                }
            }
        }
        return true
    }

    private suspend fun checkForUpdate(addon: CatalogAddon) {
        if (_states.value.values.any { it == AddonState.Installing }) return
        val current = _states.value[addon.id] as? AddonState.Installed ?: return
        if (now() - prefs.lastUpdateCheckMillis(addon.id) < UPDATE_INTERVAL_MILLIS) return
        try {
            // A list of one, so that "no update" (null) can be told from "no answer in time" (the whole result null).
            val answer = withTimeoutOrNull(UPDATE_TIMEOUT_MILLIS) { listOf(host.checkForUpdate(addon.id)) }
            if (answer == null) {
                log("${addon.name} update check timed out")
                return
            }
            val updated = answer.single()
            prefs.setLastUpdateCheckMillis(addon.id, now())
            if (updated != null) {
                prefs.setUpdateBlocked(addon.id, false)
                set(addon.id, updated.toState())
                log("${addon.name} updated to ${updated.version}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtensionInstallException) {
            if (e.error == ExtensionInstallError.CANCELLED) {
                // The engine denied an update that asks for more access. Never granted here, never dropped silently.
                log("${addon.name} update needs more access")
                prefs.setLastUpdateCheckMillis(addon.id, now())
                prefs.setUpdateBlocked(addon.id, true)
                set(addon.id, current.copy(updateBlocked = true))
            } else {
                log("${addon.name} update check failed: ${e.error}")
            }
        } catch (e: Exception) {
            // The Installed state stays as it was, and the time is not recorded: the next launch tries again.
            log("${addon.name} update check failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun runInstall(addon: CatalogAddon, confirm: suspend (AddonAccess) -> Boolean) {
        log("${addon.name} install started")
        val next =
            try {
                val installed = installWithTimeout(addon, confirm)
                when {
                    installed == null -> {
                        log("${addon.name} install timed out")
                        AddonState.Failed(AddonFailure.TIMEOUT)
                    }
                    installed.id != addon.id -> {
                        log("${addon.name} install returned another extension")
                        AddonState.Failed(AddonFailure.WRONG_EXTENSION)
                    }
                    else -> {
                        log("${addon.name} installed ${installed.version}")
                        prefs.setUpdateBlocked(addon.id, false)
                        prefs.setLastUpdateCheckMillis(addon.id, now())
                        installed.toState()
                    }
                }
            } catch (e: CancellationException) {
                set(addon.id, AddonState.NotInstalled)
                throw e
            } catch (e: ExtensionInstallException) {
                log("${addon.name} install ended: ${e.error}")
                if (e.error == ExtensionInstallError.CANCELLED) AddonState.NotInstalled else AddonState.Failed(e.error.toFailure())
            } catch (e: Exception) {
                log("${addon.name} install failed: ${e.javaClass.simpleName}")
                AddonState.Failed(AddonFailure.UNKNOWN)
            }
        set(addon.id, next)
    }

    /** Null on timeout. The clock stops while [confirm] waits for the user. */
    private suspend fun installWithTimeout(
        addon: CatalogAddon,
        confirm: suspend (AddonAccess) -> Boolean,
    ): InstalledExtension? =
        coroutineScope {
            val waitingForUser = AtomicBoolean(false)
            val timedOut = AtomicBoolean(false)
            val op =
                async {
                    host.install(addon.xpiUrl, addon.id) { access ->
                        waitingForUser.set(true)
                        try {
                            // A confirm that never answers must not hold the one-at-a-time lock for ever.
                            withTimeoutOrNull(confirmLimitMillis) { confirm(access) }
                                ?: false.also { log("${addon.name} install answer timed out") }
                        } finally {
                            waitingForUser.set(false)
                        }
                    }
                }
            val watchdog =
                launch {
                    var spent = 0L
                    while (spent < installTimeoutMillis) {
                        delay(TICK_MILLIS)
                        if (!waitingForUser.get()) spent += TICK_MILLIS
                    }
                    timedOut.set(true)
                    op.cancel()
                }
            try {
                op.await()
            } catch (e: CancellationException) {
                if (timedOut.get() && isActive) null else throw e
            } finally {
                watchdog.cancel()
            }
        }

    private suspend fun reload() {
        val installed =
            try {
                host.list().associateBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("add-on list failed: ${e.javaClass.simpleName}")
                return
            }
        _states.update { old ->
            AddonCatalog.entries.associate { addon ->
                val found = installed[addon.id]
                val previous = old[addon.id]
                addon.id to
                    when {
                        found != null -> found.toState()
                        // Keep a queued install and the last failure (so the screen can show why) until the next action.
                        previous == AddonState.Installing || previous is AddonState.Failed -> previous
                        else -> AddonState.NotInstalled
                    }
            }
        }
    }

    private fun InstalledExtension.toState() =
        AddonState.Installed(
            version = version,
            enabled = enabled,
            allowedInPrivate = allowedInPrivate,
            optionsPageUrl = optionsPageUrl,
            updateBlocked = prefs.updateBlocked(id),
            disabledByBrowser = disabledReason == ExtensionDisabledReason.BLOCKLIST || disabledReason == ExtensionDisabledReason.SIGNATURE,
            disabledOther = disabledReason == ExtensionDisabledReason.OTHER,
        )

    private fun set(id: String, state: AddonState) {
        _states.update { it + (id to state) }
    }

    companion object {
        const val DEFAULT_INSTALL_TIMEOUT_MILLIS = 180_000L
        const val DEFAULT_CONFIRM_LIMIT_MILLIS = 5 * 60_000L
        const val UPDATE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        private const val UPDATE_TIMEOUT_MILLIS = 3 * 60_000L
        private const val TICK_MILLIS = 1_000L
    }
}


private fun ExtensionInstallError.toFailure(): AddonFailure =
    when (this) {
        ExtensionInstallError.NETWORK -> AddonFailure.NETWORK
        ExtensionInstallError.SIGNATURE -> AddonFailure.SIGNATURE
        ExtensionInstallError.INCOMPATIBLE -> AddonFailure.INCOMPATIBLE
        ExtensionInstallError.BLOCKED -> AddonFailure.BLOCKED
        ExtensionInstallError.CORRUPT -> AddonFailure.CORRUPT
        ExtensionInstallError.WRONG_EXTENSION -> AddonFailure.WRONG_EXTENSION
        // A refused prompt is not a failure; callers map it to NotInstalled before this.
        ExtensionInstallError.CANCELLED,
        ExtensionInstallError.UNKNOWN,
        -> AddonFailure.UNKNOWN
    }

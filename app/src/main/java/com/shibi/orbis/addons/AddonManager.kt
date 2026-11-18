package com.shibi.orbis.addons

import com.shibi.orbis.engine.AddonAccess
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
    private val installTimeoutMillis: Long = DEFAULT_INSTALL_TIMEOUT_MILLIS,
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
     * does not count against the install timeout. Returns false, and asks the engine nothing, when [id] is not in the
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
                            confirm(access)
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

    private fun set(id: String, state: AddonState) {
        _states.update { it + (id to state) }
    }

    companion object {
        const val DEFAULT_INSTALL_TIMEOUT_MILLIS = 180_000L
        private const val TICK_MILLIS = 1_000L
    }
}

private fun InstalledExtension.toState() = AddonState.Installed(version, enabled, allowedInPrivate, optionsPageUrl)

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

package com.shibi.orbis.ubo

import com.shibi.orbis.engine.ExtensionHost
import com.shibi.orbis.engine.ExtensionInstallException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the optional uBlock Origin install from Mozilla Add-ons. Nothing is bundled; the install
 * only starts after the user accepted (or accepted earlier and it never succeeded).
 * [scope] must outlive screens so leaving a screen never cancels an install.
 */
class UboOnboardingController(
    private val host: ExtensionHost,
    private val prefs: UboPrefs,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    private val installTimeoutMillis: Long = DEFAULT_INSTALL_TIMEOUT_MILLIS,
) {
    private val _state = MutableStateFlow<UboState>(UboState.Checking)
    val state: StateFlow<UboState> = _state.asStateFlow()

    private val _showOnboarding = MutableStateFlow(prefs.decision == UboDecision.UNDECIDED)
    val showOnboarding: StateFlow<Boolean> = _showOnboarding.asStateFlow()

    private val opLock = Mutex()
    private var startJob: Job? = null
    private var installJob: Job? = null

    fun start() {
        if (startJob != null) return
        _showOnboarding.value = prefs.decision == UboDecision.UNDECIDED
        startJob = scope.launch { runStart() }
    }

    fun accept() = beginInstall()

    /** Same as [accept]; kept separate so call sites read naturally (Retry button). */
    fun retry() = beginInstall()

    /** Works from any state, including Failed ("Continue without uBlock Origin"). */
    fun decline() {
        installJob?.cancel()
        prefs.decision = UboDecision.DECLINED
        _showOnboarding.value = false
        if (_state.value !is UboState.Installed) _state.value = UboState.NotInstalled
        log("uBO declined")
    }

    fun setEnabled(enabled: Boolean) {
        if (_state.value !is UboState.Installed) return
        scope.launch {
            opLock.withLock {
                try {
                    _state.value = host.setEnabled(UBO_ID, enabled).toUboState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("uBO setEnabled($enabled) failed: ${e.javaClass.simpleName}")
                    refresh()
                }
            }
        }
    }

    /** Removes uBlock Origin and records DECLINED so it is never reinstalled automatically. */
    fun remove() {
        if (_state.value !is UboState.Installed) return
        prefs.decision = UboDecision.DECLINED
        scope.launch {
            opLock.withLock {
                try {
                    host.uninstall(UBO_ID)
                    _state.value = UboState.NotInstalled
                    log("uBO removed")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("uBO remove failed: ${e.javaClass.simpleName}")
                    refresh()
                }
            }
        }
    }

    private fun beginInstall() {
        if (installJob?.isActive == true) return
        prefs.decision = UboDecision.ACCEPTED
        val pendingStart = startJob
        installJob =
            scope.launch {
                // Let the startup lookup (and any auto-install it started) finish first.
                pendingStart?.join()
                if (_state.value is UboState.Installed) {
                    _showOnboarding.value = false
                } else {
                    runInstall()
                }
            }
    }

    private suspend fun runStart() {
        val existing =
            try {
                host.find(UBO_ID)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("uBO lookup failed: ${e.javaClass.simpleName}")
                _state.value = UboState.NotInstalled
                return
            }
        when {
            existing != null -> {
                prefs.decision = UboDecision.ACCEPTED
                _showOnboarding.value = false
                _state.value = existing.toUboState()
                allowInPrivateBrowsing()
                checkForUpdateIfDue()
            }
            // Accepted earlier but not installed (e.g. the first attempt failed offline): try once more.
            prefs.decision == UboDecision.ACCEPTED -> runInstall()
            else -> _state.value = UboState.NotInstalled
        }
    }

    private suspend fun runInstall() {
        _state.value = UboState.Installing
        log("uBO install started")
        try {
            val installed = withTimeoutOrNull(installTimeoutMillis) { host.install(UBO_XPI_URL, UBO_ID) }
            if (installed == null) {
                log("uBO install timed out")
                _state.value = UboState.Failed(UboFailure.TIMEOUT)
            } else {
                prefs.lastUpdateCheckMillis = now()
                _state.value = installed.toUboState()
                _showOnboarding.value = false
                log("uBO installed ${installed.version}")
                allowInPrivateBrowsing()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtensionInstallException) {
            log("uBO install failed: ${e.error}")
            _state.value = UboState.Failed(e.error.toUboFailure())
        } catch (e: Exception) {
            log("uBO install failed: ${e.javaClass.simpleName}")
            _state.value = UboState.Failed(UboFailure.UNKNOWN)
        }
    }

    /** The blocker should also work in private tabs. A failure is only logged: it never changes the state. */
    private suspend fun allowInPrivateBrowsing() {
        try {
            host.allowInPrivateBrowsing(UBO_ID)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("uBO private browsing permission failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun checkForUpdateIfDue() {
        if (now() - prefs.lastUpdateCheckMillis < UPDATE_INTERVAL_MILLIS) return
        try {
            val updated = host.checkForUpdate(UBO_ID)
            prefs.lastUpdateCheckMillis = now()
            if (updated != null) {
                _state.value = updated.toUboState()
                log("uBO updated to ${updated.version}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never downgrades the Installed state.
            log("uBO update check failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun refresh() {
        try {
            val found = host.find(UBO_ID)
            _state.value = found?.toUboState() ?: UboState.NotInstalled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("uBO state refresh failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_INSTALL_TIMEOUT_MILLIS = 180_000L
        const val UPDATE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}

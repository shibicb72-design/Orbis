package com.shibi.orbis.clear

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

sealed interface ClearState {
    object Idle : ClearState
    object Running : ClearState
    data class Finished(val outcome: ClearOutcome) : ClearState
}

/**
 * Runs a clear in the app scope, so it carries on if the screen goes away, and holds its state for the screen. Main
 * thread. A second [start] while one is running is ignored.
 */
class ClearController(
    private val clearer: BrowsingDataClearer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ClearState>(ClearState.Idle)
    val state: StateFlow<ClearState> = _state.asStateFlow()

    /** True if a clear was started; false if one is already running. */
    fun start(selection: ClearSelection): Boolean {
        if (_state.getAndUpdate { ClearState.Running } is ClearState.Running) return false
        scope.launch {
            try {
                _state.value = ClearState.Finished(clearer.run(selection))
            } catch (e: CancellationException) {
                _state.value = ClearState.Idle
                throw e
            } catch (e: Exception) {
                // The clearer isolates its categories, so this is not expected; never leave the screen locked.
                _state.value = ClearState.Finished(ClearOutcome.Incomplete(selection.categories.toSet(), emptySet()))
            }
        }
        return true
    }

    /** Forgets a finished result so the next visit to the screen does not show it. A running clear is not affected. */
    fun dismissResult() {
        if (_state.value is ClearState.Finished) _state.value = ClearState.Idle
    }
}

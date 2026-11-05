package com.shibi.orbis.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.shibi.orbis.debug.DebugLog

class DebugLogViewModel(private val debugLog: DebugLog) : ViewModel() {
    val version get() = debugLog.version

    fun lines(): List<String> = debugLog.lines()

    fun copyText(): String = debugLog.readAll()

    fun clear() = debugLog.clear()

    companion object {
        fun factory(debugLog: DebugLog): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DebugLogViewModel(debugLog) as T
            }
    }
}
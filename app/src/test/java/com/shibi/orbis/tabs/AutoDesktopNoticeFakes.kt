package com.shibi.orbis.tabs

import android.view.View
import com.shibi.orbis.engine.BrowserSession
import com.shibi.orbis.engine.SessionHost
import com.shibi.orbis.engine.SessionInit
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.SitePrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A session whose "desktop site kept" event is driven by the test. Like the real one, the event is not replayed. */
internal class NoticeSession(init: SessionInit = SessionInit()) : BrowserSession {
    override val isPrivate = init.isPrivate
    val stateFlow = MutableStateFlow(SessionState(desktopMode = init.desktopMode, url = "https://blocked.example/page"))
    override val state: StateFlow<SessionState> = stateFlow
    override val prompts: StateFlow<SitePrompt?> = MutableStateFlow(null)
    val kept = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val autoDesktopKept: Flow<Unit> = kept
    override var host: SessionHost? = null
    var closed = false
    val desktopCalls = mutableListOf<Boolean>()

    override fun attachTo(contentView: View) = Unit
    override fun detachFrom(contentView: View) = Unit
    override fun loadUrl(url: String) = Unit
    override fun loadExternalUrl(url: String) = Unit
    override fun loadImportedFile(url: String) = Unit
    override fun setDesktopMode(enabled: Boolean) {
        desktopCalls += enabled
        stateFlow.value = stateFlow.value.copy(desktopMode = enabled)
    }
    override fun goBack() = false
    override fun goForward() = false
    override fun reload() = Unit
    override fun stop() = Unit
    override fun find(query: String, backwards: Boolean) = Unit
    override fun clearFind() = Unit
    override fun setActive(active: Boolean) = Unit
    override fun savedState(): String? = null
    override fun close() { closed = true }
}

internal class NoticeStore : TabStore {
    override fun load(): TabsSnapshot? = null
    override fun save(snapshot: TabsSnapshot) = Unit
}

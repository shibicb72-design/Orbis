package com.shibi.orbis.ui.browser

import com.shibi.orbis.engine.SessionState

/**
 * The lock is shown only when Gecko reported the committed page as secure ([SessionState.secure]), never from the URL
 * scheme alone. The https check is a second guard: a plain-http page never shows a lock, whatever the state says.
 */
internal fun showsLock(state: SessionState): Boolean = state.secure && state.url.startsWith("https://", ignoreCase = true)

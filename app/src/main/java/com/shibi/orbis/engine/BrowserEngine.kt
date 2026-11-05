package com.shibi.orbis.engine

import android.content.Context
import android.view.View

/**
 * Thin browser-engine surface. UI and feature modules must not import GeckoView types;
 * only [com.shibi.orbis.engine.GeckoEngineImpl] may.
 */
interface BrowserEngine {
    fun warmUp()
    fun createSession(): BrowserSession
    /** Returns an engine-backed view (GeckoView behind the scenes). */
    fun createContentView(context: Context): View
    fun shutdown()
}

interface BrowserSession {
    fun attachTo(contentView: View)
    fun loadUrl(url: String)
    fun goBack(): Boolean
    fun goForward(): Boolean
    fun reload()
    fun stop()
    fun close()
}
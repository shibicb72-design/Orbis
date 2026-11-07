package com.shibi.orbis.ui.browser

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressBarPolicyTest {
    private val privateUrl = "https://secret.example/private-page"

    @Test
    fun blankAddressesShowNothing() {
        assertEquals("", addressBarDisplayUrl(""))
        assertEquals("", addressBarDisplayUrl("about:blank"))
        assertEquals("https://a.example/", addressBarDisplayUrl("https://a.example/"))
    }

    @Test
    fun focusedBarShowsTheEditBuffer() {
        assertEquals("typ", addressBarText(true, "typ", collectedUrl = privateUrl, selectedTabUrl = "https://a.example/"))
    }

    @Test
    fun unfocusedBarRendersTheSelectedTabNotTheStaleCollectedState() {
        // A frame after switching from a private tab to a new normal tab: the collected session state still holds the
        // private tab's address, the selected tab is blank.
        assertEquals("", addressBarText(false, "", collectedUrl = privateUrl, selectedTabUrl = ""))
        assertEquals("", addressBarText(false, "", collectedUrl = privateUrl, selectedTabUrl = "about:blank"))
        // Normal then new private tab.
        assertEquals("", addressBarText(false, "", collectedUrl = "https://a.example/", selectedTabUrl = ""))
        // A loaded tab shows its own address even when the collected one is another tab's.
        assertEquals("https://b.example/", addressBarText(false, "", collectedUrl = privateUrl, selectedTabUrl = "https://b.example/"))
    }

    @Test
    fun focusBufferUsesTheSessionAddressElseTheTabsOwn() {
        assertEquals("https://live.example/", addressBarTextOnFocus("https://live.example/", "https://stored.example/"))
        // The TabManager.onSessionState fallback: a fresh session reports an empty url, the tab keeps the stored one.
        assertEquals("https://stored.example/", addressBarTextOnFocus("", "https://stored.example/"))
        assertEquals("", addressBarTextOnFocus("", ""))
        assertEquals("", addressBarTextOnFocus("about:blank", ""))
    }

    @Test
    fun onlyNormalTabsMaySaveTheirTypedTextInInstanceState() {
        assertTrue(addressBarTextIsSaveable(isPrivate = false))
        assertFalse(addressBarTextIsSaveable(isPrivate = true))
    }

    @Test
    fun imeOptionsFlagIsAddedForPrivateAndLeftAloneOtherwise() {
        val base = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertEquals(base or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, addressBarImeOptions(base, isPrivate = true))
        assertEquals(base, addressBarImeOptions(base, isPrivate = false))
    }

    private val connection: InputConnection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(InputConnection::class.java)) { _, _, _ -> null } as InputConnection

    /** Stands in for Compose's own request: it fills in imeOptions, replacing whatever was there. */
    private inner class FakeInner(private val written: Int) : PlatformTextInputMethodRequest {
        var calls = 0

        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
            calls++
            outAttributes.imeOptions = written
            return connection
        }
    }

    @Test
    fun interceptorAddsTheFlagAfterTheInnerRequestAndKeepsItsValue() {
        val written = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val inner = FakeInner(written)
        val info = EditorInfo()
        val result = NoPersonalizedLearningRequest(inner).createInputConnection(info)
        assertEquals(1, inner.calls)
        assertSame(connection, result)
        // Fails if the flag were set before the inner call (the inner call overwrites imeOptions), or not set at all.
        assertEquals(written or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, info.imeOptions)
        assertEquals(EditorInfo.IME_ACTION_GO, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
    }

    @Test
    fun interceptorFlagSurvivesWhenInnerWritesNoOtherOptions() {
        val info = EditorInfo()
        NoPersonalizedLearningRequest(FakeInner(0)).createInputConnection(info)
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, info.imeOptions)
    }
}

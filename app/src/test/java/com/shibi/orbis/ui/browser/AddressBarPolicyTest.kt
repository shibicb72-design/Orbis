package com.shibi.orbis.ui.browser

import android.view.inputmethod.EditorInfo
import com.shibi.orbis.tabs.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressBarPolicyTest {
    private val privateUrl = "https://secret.example/private-page"

    private fun tab(id: Long, url: String, isPrivate: Boolean) =
        Tab(id, null, "", url, false, null, null, isPrivate)

    @Test
    fun blankAddressesShowNothing() {
        assertEquals("", addressBarDisplayUrl(""))
        assertEquals("", addressBarDisplayUrl("about:blank"))
        assertEquals("https://a.example/", addressBarDisplayUrl("https://a.example/"))
    }

    @Test
    fun focusedBarShowsTheEditBufferUnfocusedShowsTheCommittedAddress() {
        assertEquals("typ", addressBarText(focused = true, typed = "typ", committedUrl = "https://a.example/"))
        assertEquals("https://a.example/", addressBarText(focused = false, typed = "typ", committedUrl = "https://a.example/"))
        assertEquals("", addressBarText(focused = false, typed = "typ", committedUrl = "about:blank"))
    }

    @Test
    fun newNormalTabAfterPrivateTabNeverShowsThePrivateUrl() {
        val previous = tab(1, privateUrl, isPrivate = true)
        val fresh = tab(2, "", isPrivate = false)
        // Unfocused render and the buffer taken on focus both come from the newly selected tab only.
        assertEquals("", addressBarText(focused = false, typed = "", committedUrl = fresh.url))
        assertEquals("", addressBarTextOnFocus(fresh.url))
        assertFalse(addressBarText(false, "", fresh.url).contains(previous.url))
        assertFalse(addressBarTextOnFocus(fresh.url).contains(previous.url))
    }

    @Test
    fun newPrivateTabAfterNormalTabStartsEmpty() {
        val previous = tab(1, "https://a.example/", isPrivate = false)
        val fresh = tab(2, "", isPrivate = true)
        assertEquals("", addressBarText(focused = false, typed = "", committedUrl = fresh.url))
        assertEquals("", addressBarTextOnFocus(fresh.url))
        assertFalse(addressBarTextOnFocus(fresh.url).contains(previous.url))
    }

    @Test
    fun focusingALoadedTabEditsItsOwnAddress() {
        assertEquals("https://a.example/", addressBarTextOnFocus("https://a.example/"))
    }

    @Test
    fun onlyNormalTabsMaySaveTheirTypedTextInInstanceState() {
        assertTrue(addressBarTextIsSaveable(isPrivate = false))
        assertFalse(addressBarTextIsSaveable(isPrivate = true))
    }

    @Test
    fun privateTabAddsNoPersonalizedLearningToExistingImeOptions() {
        val base = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val flagged = addressBarImeOptions(base, isPrivate = true)
        assertEquals(base or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, flagged)
        assertTrue(flagged and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(EditorInfo.IME_ACTION_GO, flagged and EditorInfo.IME_MASK_ACTION)
        // Idempotent.
        assertEquals(flagged, addressBarImeOptions(flagged, isPrivate = true))
    }

    @Test
    fun normalTabImeOptionsAreUnchanged() {
        val base = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertEquals(base, addressBarImeOptions(base, isPrivate = false))
        assertEquals(0, addressBarImeOptions(0, isPrivate = false) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }
}

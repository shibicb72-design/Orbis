package com.shibi.orbis.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 1: the resting display never rewrites the policy-selected address; it only decides whether to draw it. */
class AddressDisplayTest {
    // Varied shapes the bar must show verbatim: userinfo (a classic spoof shape), ports, IPv6, encoded and Unicode
    // paths, an IDN host, RTL text in the path, a very long host, a search URL and a data-ish long path.
    private val varied =
        listOf(
            "https://example.org/",
            "https://user:pw@login.example.com.evil.test/path",
            "http://127.0.0.1:18096/page/base",
            "http://[2001:db8::1]:8080/a?b=c#d",
            "https://example.org/caf%C3%A9/%E2%9C%93?q=a%20b",
            "https://bücher.example/straße",
            "https://xn--bcher-kva.example/",
            "https://example.org/שלום/مرحبا",
            "https://" + "very-long-subdomain-".repeat(8) + "example.com/",
            "https://duckduckgo.com/?q=hello+world",
            "https://example.org/" + "segment/".repeat(40),
        )

    @Test
    fun restingTextIsTheBarTextItselfForEveryShape() {
        for (url in varied) assertEquals(url, addressBarRestingText(focused = false, barText = url))
    }

    @Test
    fun restingTextComesFromThePolicyNotTheCollectedState() {
        // The committed url of the selected tab wins over a stale collected one (a private tab's, a frame after a switch).
        val text = addressBarText(false, typed = "", collectedUrl = "https://private.example/", selectedTabUrl = "https://normal.example/")
        assertEquals("https://normal.example/", addressBarRestingText(false, text))
        // A pending target wins until it resolves.
        val pending = addressBarText(false, "", "https://x/", "https://normal.example/", pendingSubmit = "https://target.example/")
        assertEquals("https://target.example/", addressBarRestingText(false, pending))
    }

    @Test
    fun noRestingLineWhileEditingOrForABlankTab() {
        assertNull(addressBarRestingText(focused = true, barText = "https://example.org/"))
        assertNull(addressBarRestingText(focused = false, barText = ""))
        assertNull(addressBarRestingText(false, addressBarText(false, "", "", selectedTabUrl = "about:blank")))
    }

    @Test
    fun aCommitEndsOnlyAnUntouchedEdit() {
        assertTrue(addressBarLeavesEditOnCommit(focused = true, editedSinceFocus = false, newTabUrl = "https://linked.example/"))
        assertFalse("typed text is never thrown away", addressBarLeavesEditOnCommit(true, editedSinceFocus = true, newTabUrl = "https://linked.example/"))
        assertFalse("not editing: nothing to end", addressBarLeavesEditOnCommit(false, false, "https://linked.example/"))
        assertFalse("a blank tab (New tab itself) keeps its focus", addressBarLeavesEditOnCommit(true, false, ""))
        assertFalse(addressBarLeavesEditOnCommit(true, false, "about:blank"))
    }

    @Test
    fun pendingTargetIsFlaggedOnlyAtRest() {
        assertTrue(addressBarShowsPendingTarget(focused = false, pendingSubmit = "https://t.example/"))
        assertFalse(addressBarShowsPendingTarget(focused = true, pendingSubmit = "https://t.example/"))
        assertFalse(addressBarShowsPendingTarget(focused = false, pendingSubmit = null))
        assertFalse("a failed submit keeps its text but is not loading", addressBarShowsPendingTarget(false, "https://t.example/", failed = true))
    }
}

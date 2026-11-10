package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSchemeTest {
    @Test
    fun appSchemesGoToApps() {
        listOf("tel:+1555", "mailto:a@b.c", "intent://x#Intent;end", "market://details?id=a", "zoommtg://join", "SMS:123")
            .forEach { assertTrue(it, isExternalScheme(it)) }
    }

    @Test
    fun engineSchemesStayInTheEngine() {
        listOf("https://a.example/", "http://a", "about:blank", "data:text/html,x", "blob:https://a/1", "javascript:void(0)", "file:///sdcard/x", "content://x", "moz-extension://id/p")
            .forEach { assertFalse(it, isExternalScheme(it)) }
    }

    @Test
    fun externalLaunchNeedsATap() {
        assertTrue(externalLaunchAllowed(hasUserGesture = true, isRedirect = false, msSinceGestureNavigation = null))
        // A redirect shortly after a tapped link (tracking or store links) still works.
        assertTrue(externalLaunchAllowed(hasUserGesture = false, isRedirect = true, msSinceGestureNavigation = 800))
        // Seen on the emulator: script -> 302 -> tel: with no tap raised the question. It must not.
        assertFalse(externalLaunchAllowed(hasUserGesture = false, isRedirect = true, msSinceGestureNavigation = null))
        assertFalse(externalLaunchAllowed(hasUserGesture = false, isRedirect = true, msSinceGestureNavigation = EXTERNAL_REDIRECT_GRACE_MS + 1))
        assertFalse(externalLaunchAllowed(hasUserGesture = false, isRedirect = false, msSinceGestureNavigation = 10))
    }

    @Test
    fun intentLinksCannotPointAtFilesOrProviders() {
        listOf("file", "content", "FILE", "Content").forEach { assertTrue(it, intentDataBlocked(it)) }
        listOf(null, "https", "tel", "geo", "market").forEach { assertFalse(it.toString(), intentDataBlocked(it)) }
    }

    @Test
    fun schemeParsing() {
        assertEquals("tel", schemeOf("TEL:+1"))
        assertNull(schemeOf("no scheme here"))
        assertNull(schemeOf(":x"))
        assertNull(schemeOf("1abc:x"))
        assertFalse(isExternalScheme("/relative/path"))
    }

    @Test
    fun invisibleHandlerIsLaunchedAndAVisibleOneIsAsked() {
        // resolveActivity == null is not "no app" (Android 11 package visibility).
        assertEquals(ExternalOpenChoice.LAUNCH, externalOpenChoice(hasIntent = true, resolvable = false))
        assertEquals(ExternalOpenChoice.ASK, externalOpenChoice(hasIntent = true, resolvable = true))
        assertEquals(ExternalOpenChoice.MISS, externalOpenChoice(hasIntent = false, resolvable = false))
    }

    @Test
    fun missingAppUsesWebFallbackThenTheStoreThenGivesUp() {
        assertEquals(
            ExternalMiss.OpenWeb("https://example.com/a"),
            planExternalMiss("https://example.com/a", "com.example.app"),
        )
        assertEquals(
            ExternalMiss.OpenMarket("market://details?id=com.example.app"),
            planExternalMiss(null, "com.example.app"),
        )
        assertEquals(
            ExternalMiss.OpenMarket("market://details?id=com.example.app"),
            planExternalMiss("javascript:alert(1)", "com.example.app"),
        )
        assertEquals(ExternalMiss.NoHandler, planExternalMiss(null, null))
        assertEquals(ExternalMiss.NoHandler, planExternalMiss("magnet:?xt=urn:btih:abc", "not a package"))
        assertNull(marketDetailsUri("../evil"))
        assertNull(marketDetailsUri("singlelabel"))
        assertEquals("market://details?id=com.example.app", marketDetailsUri("com.example.app"))
    }
}

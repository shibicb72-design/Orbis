package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What externalSchemeRequest does first with a link for another app (an intent: link's web fallback without a tap). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExternalRequestStepTest {
    private fun intentLink(fallback: String?, scheme: String = "orbistest"): String {
        val extra = fallback?.let { ";S.browser_fallback_url=" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""
        return "intent://open?x=1#Intent;scheme=$scheme$extra;end"
    }

    private fun noTap(uri: String) = planExternalRequest(tapAllowed = false, questionPending = false, uri = uri)

    @Test
    fun anIntentLinkWithAnHttpsFallbackLoadsTheFallbackWithoutATap() {
        assertEquals(
            ExternalRequestStep.FollowWebFallback("https://example.com/landing?a=1&b=2"),
            noTap(intentLink("https://example.com/landing?a=1&b=2")),
        )
    }

    @Test
    fun anIntentLinkWithAnHttpFallbackLoadsTheFallbackWithoutATap() {
        // HTTPS-only mode then does what it does to any http load.
        assertEquals(ExternalRequestStep.FollowWebFallback("http://example.com/x"), noTap(intentLink("http://example.com/x")))
    }

    @Test
    fun theRigPageR4LinkLoadsItsFallback() {
        val r4 = "intent://open?x=1#Intent;scheme=orbistest;S.browser_fallback_url=http%3A%2F%2F10.0.2.2%3A8080%2Farrived;end"
        assertEquals(ExternalRequestStep.FollowWebFallback("http://10.0.2.2:8080/arrived"), noTap(r4))
    }

    @Test
    fun anIntentLinkWithNoFallbackStaysRefused() {
        assertEquals(ExternalRequestStep.BlockedNoTap, noTap(intentLink(null)))
        assertEquals(ExternalRequestStep.BlockedNoTap, noTap("intent://open#Intent;scheme=orbistest;package=com.example.app;end"))
        assertEquals(ExternalRequestStep.BlockedNoTap, noTap("intent:#Intent;end"))
    }

    @Test
    fun aFallbackThatIsNotHttpOrHttpsStaysRefused() {
        listOf(
            "intent://x#Intent;scheme=other;end",
            "file:///sdcard/secret.html",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "content://com.example.provider/doc/1",
            "myapp://open/page",
            "market://details?id=com.example.app",
            "ftp://example.com/x",
            "about:blank",
            "",
            "   ",
            "example.com/no-scheme",
        ).forEach { fallback ->
            assertEquals(fallback, ExternalRequestStep.BlockedNoTap, noTap(intentLink(fallback)))
        }
    }

    @Test
    fun aMalformedWebFallbackStaysRefused() {
        listOf("https://", "http://", "https:///path", "https://exa mple.com/", "https://example.com/\nx", "https://@/")
            .forEach { fallback -> assertEquals(fallback, ExternalRequestStep.BlockedNoTap, noTap(intentLink(fallback))) }
        // An unparseable intent: link.
        assertEquals(ExternalRequestStep.BlockedNoTap, noTap("intent://x#Intent;S.browser_fallback_url=https%3A%2F%2Fa.example%2F"))
        assertEquals(ExternalRequestStep.BlockedNoTap, noTap("intent:"))
    }

    @Test
    fun anAppsOwnSchemeWithoutATapStaysRefused() {
        listOf(
            "orbistest://open?x=1",
            "tel:+15551234",
            "mailto:a@b.example",
            "market://details?id=com.example.app",
            "magnet:?xt=urn:btih:abc123",
            // The word browser_fallback_url in a plain app link is not an intent: link's fallback.
            "orbistest://open?S.browser_fallback_url=https%3A%2F%2Fexample.com%2F",
            "orbistest://open#Intent;S.browser_fallback_url=https%3A%2F%2Fexample.com%2F;end",
        ).forEach { assertEquals(it, ExternalRequestStep.BlockedNoTap, noTap(it)) }
    }

    @Test
    fun aTappedLinkIsNeverTurnedIntoAFallbackHere() {
        // With a tap the link goes on to the app, the question and the fallback chain, as before.
        val link = intentLink("https://example.com/landing")
        assertEquals(ExternalRequestStep.Proceed, planExternalRequest(tapAllowed = true, questionPending = false, uri = link))
        assertEquals(ExternalRequestStep.Proceed, planExternalRequest(tapAllowed = true, questionPending = false, uri = "orbistest://open"))
    }

    @Test
    fun aTappedLinkWhileOneQuestionIsOpenIsRefusedAndItsFallbackIsNotFollowed() {
        val link = intentLink("https://example.com/landing")
        assertEquals(ExternalRequestStep.BlockedPending, planExternalRequest(tapAllowed = true, questionPending = true, uri = link))
        assertEquals(ExternalRequestStep.BlockedPending, planExternalRequest(tapAllowed = true, questionPending = true, uri = "tel:+1555"))
    }

    @Test
    fun withoutATapAnOpenQuestionDoesNotChangeTheOutcome() {
        // The "one question pending" refusal is for tapped links; a no-tap link never asks a question at all.
        val link = intentLink("https://example.com/landing")
        assertEquals(
            ExternalRequestStep.FollowWebFallback("https://example.com/landing"),
            planExternalRequest(tapAllowed = false, questionPending = true, uri = link),
        )
        assertEquals(ExternalRequestStep.BlockedNoTap, planExternalRequest(tapAllowed = false, questionPending = true, uri = "tel:+1555"))
    }

    @Test
    fun aNoTapLinkNeverProceeds() {
        listOf(
            intentLink("https://example.com/a"),
            intentLink(null),
            intentLink("javascript:alert(1)"),
            "orbistest://open",
            "tel:+1555",
            "",
        ).forEach { uri ->
            listOf(false, true).forEach { pending ->
                val step = planExternalRequest(tapAllowed = false, questionPending = pending, uri = uri)
                assertEquals(uri, false, step == ExternalRequestStep.Proceed || step == ExternalRequestStep.BlockedPending)
            }
        }
    }
}

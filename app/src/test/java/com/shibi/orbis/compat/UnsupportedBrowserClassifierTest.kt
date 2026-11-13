package com.shibi.orbis.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class UnsupportedBrowserClassifierTest {
    private fun sample(
        text: String,
        title: String = "",
        headlines: List<String> = emptyList(),
        blockPath: Boolean = false,
    ) = PageSample(title, headlines, text, blockPath)

    private val au10tixWide =
        "This Browser is unsupported\nPlease switch to one of the following Chrome/Firefox/Safari/Edge\n" +
            "Copy and paste this URL\nhttps://example.test/x"
    private val au10tixNarrow =
        "This Browser is unsupported Please\nswitch to one of the following\nChrome/Safari/Edge\n" +
            "Copy and paste this URL\nhttps://example.test/x"
    private val noLonger = "We no longer support your browser. Please install Chrome or Edge."

    // ---- positives ----

    @Test
    fun au10tixWideLayout() {
        assertEquals(true, isUnsupportedBrowserPage(sample(au10tixWide, title = "Secure.me - AU10TIX")))
    }

    @Test
    fun au10tixNarrowLayout() {
        assertEquals(true, isUnsupportedBrowserPage(sample(au10tixNarrow, title = "Secure.me - AU10TIX")))
    }

    @Test
    fun headlineOnly() {
        assertEquals(true, isUnsupportedBrowserPage(sample("Please come back later.", title = "Unsupported browser")))
    }

    @Test
    fun headlineListEntryAlone() {
        assertEquals(
            true,
            isUnsupportedBrowserPage(sample("Your browser is not supported", headlines = listOf("Your browser is not supported"))),
        )
    }

    @Test
    fun pathOnlyConfirmation() {
        assertEquals(true, isUnsupportedBrowserPage(sample("Your browser is not supported.", blockPath = true)))
    }

    @Test
    fun noLongerSupportWithRemedy() {
        assertEquals(true, isUnsupportedBrowserPage(sample(noLonger)))
    }

    @Test
    fun exactly800CharactersAroundAPositive() {
        val text = au10tixWide + " " + "x".repeat(800 - au10tixWide.length - 1)
        assertEquals(800, text.length)
        assertEquals(true, isUnsupportedBrowserPage(sample(text)))
    }

    @Test
    fun isntSupportedWithRemedy_bothApostrophes() {
        assertEquals(true, isUnsupportedBrowserPage(sample("Your browser isn't supported. Please use Chrome or Firefox.")))
        assertEquals(true, isUnsupportedBrowserPage(sample("Your browser isn’t supported. Please use Chrome or Firefox.")))
    }

    @Test
    fun isntCompatibleInTitle_bothApostrophes() {
        assertEquals(true, isUnsupportedBrowserPage(sample("Sorry.", title = "This browser isn't compatible")))
        assertEquals(true, isUnsupportedBrowserPage(sample("Sorry.", title = "This browser isn’t compatible")))
    }

    // ---- negatives ----

    @Test
    fun supportedBrowserIsNotABlock() {
        assertEquals(false, isUnsupportedBrowserPage(sample("Your browser is supported.", title = "Supported browser")))
    }

    @Test
    fun longArticleContainingEveryPositiveSentence() {
        val body = listOf(au10tixWide, au10tixNarrow, "Please come back later.", noLonger).joinToString("\n")
        val text = body + "\n" + "Lorem ipsum dolor sit amet. ".repeat(120)
        assertEquals(true, text.length >= 3000)
        assertEquals(false, isUnsupportedBrowserPage(sample(text, title = "Unsupported browser", blockPath = true)))
    }

    @Test
    fun exactly801CharactersAroundAPositive() {
        val text = au10tixWide + " " + "x".repeat(801 - au10tixWide.length - 1)
        assertEquals(801, text.length)
        assertEquals(false, isUnsupportedBrowserPage(sample(text, title = "Unsupported browser")))
    }

    @Test
    fun browserNamesWithoutBlock() {
        assertEquals(false, isUnsupportedBrowserPage(sample("Download Chrome")))
        assertEquals(false, isUnsupportedBrowserPage(sample("Get Firefox for Android")))
    }

    @Test
    fun supportedBrowsersHelpPage() {
        val s = sample(
            "Chrome\nFirefox\nSafari\nEdge\nOpera",
            title = "Help",
            headlines = listOf("Supported browsers"),
        )
        assertEquals(false, isUnsupportedBrowserPage(s))
    }

    @Test
    fun blockPhraseWithoutAnyConfirmation() {
        assertEquals(false, isUnsupportedBrowserPage(sample("Your browser is not supported.")))
    }

    @Test
    fun loginPageRecommendingBrowsers() {
        assertEquals(
            false,
            isUnsupportedBrowserPage(sample("Sign in. Use Chrome or Firefox for the best experience.")),
        )
    }

    @Test
    fun cookieNoticeMentioningBrowserSettings() {
        assertEquals(
            false,
            isUnsupportedBrowserPage(sample("We use cookies. You can change this in your browser settings.")),
        )
    }

    @Test
    fun featureNotSupportedInRegion() {
        assertEquals(
            false,
            isUnsupportedBrowserPage(
                sample("This feature is not supported in your region. Try Chrome or Edge extensions."),
            ),
        )
    }

    @Test
    fun javaScriptDisabledNotice() {
        val t = "JavaScript is not supported or is disabled in your browser."
        assertEquals(false, isUnsupportedBrowserPage(sample(t)))
        assertEquals(false, isUnsupportedBrowserPage(sample(t, blockPath = true)))
    }

    @Test
    fun unsupportedVideoFormat() {
        val t = "This video format is not supported by your browser."
        assertEquals(false, isUnsupportedBrowserPage(sample(t)))
        assertEquals(false, isUnsupportedBrowserPage(sample(t, blockPath = true)))
    }

    @Test
    fun emptyStrings() {
        assertEquals(false, isUnsupportedBrowserPage(sample("")))
        assertEquals(false, isUnsupportedBrowserPage(PageSample("", listOf(""), "", false)))
    }

    @Test
    fun germanBlockPageIsNotDetected() {
        assertEquals(
            false,
            isUnsupportedBrowserPage(sample("Dieser Browser wird nicht unterstuetzt", blockPath = true)),
        )
    }

    // ---- isBlockPath ----

    @Test
    fun blockPathSegments() {
        for (p in listOf(
            "/unsupported", "/unsupported-browser", "/a/browser-not-supported/", "/browser-unsupported",
            "/outdated-browser", "/upgrade-browser", "/browser-update", "/incompatible-browser",
            "/Unsupported?x=1", "/unsupported.html", "/app/unsupported#top",
        )) {
            assertEquals(p, true, isBlockPath(p))
        }
    }

    @Test
    fun nonBlockPaths() {
        for (p in listOf(
            "", "/", "/supported", "/unsupported-features", "/blog/unsupportedness",
            "/browser", "/update", "/products?next=/unsupported",
        )) {
            assertEquals(p, false, isBlockPath(p))
        }
    }
}

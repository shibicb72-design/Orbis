package com.shibi.orbis.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeLinesTest {
    @Test
    fun loadLine_everyKind() {
        val expected =
            mapOf(
                LoadKind.COMMITTED to "committed",
                LoadKind.REDIRECTED to "redirected",
                LoadKind.DOWNLOAD to "download",
                LoadKind.APP to "app",
                LoadKind.REFUSED to "refused",
                LoadKind.STOPPED to "stopped",
                LoadKind.UNFINISHED to "unfinished",
            )
        assertEquals(LoadKind.entries.toSet(), expected.keys)
        for ((kind, text) in expected) {
            assertEquals("load: $text tab=7 ms=120", OutcomeLines.load(kind, isPrivate = false, tab = 7, ms = 120))
        }
    }

    @Test
    fun loadLine_errorCategories() {
        val expected =
            mapOf(
                ErrorCategory.UNKNOWN to "unknown",
                ErrorCategory.SECURITY to "security",
                ErrorCategory.NETWORK to "network",
                ErrorCategory.CONTENT to "content",
                ErrorCategory.URI to "uri",
                ErrorCategory.PROXY to "proxy",
                ErrorCategory.SAFEBROWSING to "safebrowsing",
            )
        assertEquals(ErrorCategory.entries.toSet(), expected.keys)
        for ((category, text) in expected) {
            assertEquals("load: error-$text tab=3 ms=40", OutcomeLines.loadError(category, isPrivate = false, tab = 3, ms = 40))
        }
    }

    @Test
    fun loadLine_everyKindWithEveryEndedValue() {
        for (kind in LoadKind.entries) {
            assertEquals("load: ${kind.text} tab=2 ms=900", OutcomeLines.load(kind, isPrivate = false, tab = 2, ms = 900))
            assertEquals("load: ${kind.text} tab=2 ms=900", OutcomeLines.load(kind, false, 2, 900, LoadEnded.YES))
            assertEquals("load: ${kind.text} tab=2 ms=900 ended=no", OutcomeLines.load(kind, false, 2, 900, LoadEnded.NO))
            assertEquals("load: ${kind.text} tab=2 ms=900 ended=stopped", OutcomeLines.load(kind, false, 2, 900, LoadEnded.STOPPED))
        }
        assertEquals(setOf(LoadEnded.YES, LoadEnded.NO, LoadEnded.STOPPED), LoadEnded.entries.toSet())
    }

    @Test
    fun pageEmptyLine_withACount_withMinusOne_andForAPrivateTab() {
        assertEquals("page empty: tab=2 blocked=14", OutcomeLines.pageEmpty(isPrivate = false, tab = 2, blocked = 14))
        assertEquals("page empty: tab=2 blocked=0", OutcomeLines.pageEmpty(isPrivate = false, tab = 2, blocked = 0))
        assertEquals("page empty: tab=2", OutcomeLines.pageEmpty(isPrivate = false, tab = 2, blocked = -1))
        assertEquals("page empty: tab=0 blocked=3", OutcomeLines.pageEmpty(isPrivate = true, tab = 77, blocked = 3))
        assertEquals("page empty: tab=0", OutcomeLines.pageEmpty(isPrivate = true, tab = 77, blocked = -1))
    }

    @Test
    fun linkIn_everyVia() {
        val expected =
            mapOf(
                LinkVia.VIEW to "view",
                LinkVia.SEND to "send",
                LinkVia.CUSTOM_TAB to "custom-tab",
                LinkVia.SHORTCUT to "shortcut",
                LinkVia.OTHER to "other",
            )
        assertEquals(LinkVia.entries.toSet(), expected.keys)
        for ((via, text) in expected) {
            assertEquals("link in: via=$text tab=5 new-tab=yes", OutcomeLines.linkIn(via, isPrivate = false, tab = 5, newTab = true))
        }
        assertEquals("link in: via=view tab=5 new-tab=no", OutcomeLines.linkIn(LinkVia.VIEW, isPrivate = false, tab = 5, newTab = false))
    }

    @Test
    fun popup_everyResult() {
        assertEquals("popup: opener=4 result=opened child=9 count=1", OutcomeLines.popup(PopupResult.OPENED, false, 4, 9, 1))
        assertEquals(
            "popup: opener=4 result=closed-at-tab-limit child=0 count=2",
            OutcomeLines.popup(PopupResult.CLOSED_AT_TAB_LIMIT, false, 4, 0, 2),
        )
        assertEquals("popup: opener=4 result=blocked child=0 count=3", OutcomeLines.popup(PopupResult.BLOCKED, false, 4, 0, 3))
        assertEquals(PopupResult.entries.size, 3)
    }

    @Test
    fun popupResult_aChildWithNoTabWasClosedAtTheLimit() {
        assertEquals(PopupResult.CLOSED_AT_TAB_LIMIT, OutcomeLines.popupResultOf(0L))
        assertEquals(PopupResult.OPENED, OutcomeLines.popupResultOf(12L))
    }

    @Test
    fun appLink_everyKindAndDecision() {
        val kinds =
            mapOf(
                AppLinkKind.INTENT to "intent",
                AppLinkKind.MARKET to "market",
                AppLinkKind.TEL to "tel",
                AppLinkKind.MAILTO to "mailto",
                AppLinkKind.SMS to "sms",
                AppLinkKind.OTHER to "other",
            )
        assertEquals(AppLinkKind.entries.toSet(), kinds.keys)
        for ((kind, text) in kinds) {
            assertEquals(
                "app link: kind=$text decision=opened asked=no tab=6",
                OutcomeLines.appLink(kind, AppLinkDecision.OPENED, asked = false, isPrivate = false, tab = 6),
            )
        }
        val decisions =
            mapOf(
                AppLinkDecision.OPENED to "opened",
                AppLinkDecision.ASKED to "asked",
                AppLinkDecision.REFUSED to "refused",
                AppLinkDecision.NO_APP to "no-app",
                AppLinkDecision.WEB_FALLBACK_FOLLOWED to "web-fallback-followed",
                AppLinkDecision.BLOCKED_IMPORT to "blocked-import",
            )
        assertEquals(AppLinkDecision.entries.toSet(), decisions.keys)
        for ((decision, text) in decisions) {
            assertEquals(
                "app link: kind=tel decision=$text asked=yes tab=6",
                OutcomeLines.appLink(AppLinkKind.TEL, decision, asked = true, isPrivate = false, tab = 6),
            )
        }
    }

    @Test
    fun processKilled_countsAndSelection() {
        assertEquals(
            "page process killed: tabs=12 awake=3 selected=yes",
            OutcomeLines.processKilled(isPrivate = false, tabs = 12, awake = 3, selected = true),
        )
        assertEquals(
            "page process killed: tabs=12 awake=3 selected=no",
            OutcomeLines.processKilled(isPrivate = false, tabs = 12, awake = 3, selected = false),
        )
    }

    @Test
    fun desktopMode_everyWhy() {
        val expected =
            mapOf(
                DesktopWhy.USER to "user",
                DesktopWhy.SITE_SETTING to "site-setting",
                DesktopWhy.AUTO_BLOCKED_PAGE to "auto-blocked-page",
                DesktopWhy.AUTO_EMPTY_PAGE to "auto-empty-page",
                DesktopWhy.AUTO_DROPPED to "auto-dropped",
                DesktopWhy.RESTORED to "restored",
            )
        assertEquals(DesktopWhy.entries.toSet(), expected.keys)
        for ((why, text) in expected) {
            assertEquals("desktop mode: on tab=8 why=$text", OutcomeLines.desktopMode(true, why, isPrivate = false, tab = 8))
            assertEquals("desktop mode: off tab=8 why=$text", OutcomeLines.desktopMode(false, why, isPrivate = false, tab = 8))
        }
    }

    @Test
    fun aPrivateTabNumberIsNeverWritten() {
        val lines =
            listOf(
                OutcomeLines.load(LoadKind.COMMITTED, isPrivate = true, tab = 77, ms = 1),
                OutcomeLines.loadError(ErrorCategory.NETWORK, isPrivate = true, tab = 77, ms = 1),
                OutcomeLines.linkIn(LinkVia.VIEW, isPrivate = true, tab = 77, newTab = true),
                OutcomeLines.popup(PopupResult.OPENED, isPrivate = true, opener = 77, child = 78, count = 1),
                OutcomeLines.appLink(AppLinkKind.TEL, AppLinkDecision.ASKED, asked = true, isPrivate = true, tab = 77),
                OutcomeLines.desktopMode(true, DesktopWhy.USER, isPrivate = true, tab = 77),
                OutcomeLines.pageEmpty(isPrivate = true, tab = 77, blocked = 2),
            )
        for (line in lines) {
            assertFalse(line, line.contains("77") || line.contains("78"))
        }
        assertTrue(lines[0].contains("tab=0"))
        assertTrue(lines[3].contains("opener=0") && lines[3].contains("child=0"))
        // The counts and the selection of a private tab's kill could single it out.
        assertEquals(
            "page process killed: tabs=0 awake=0 selected=no",
            OutcomeLines.processKilled(isPrivate = true, tabs = 12, awake = 3, selected = true),
        )
        assertEquals(0L, OutcomeLines.tabNumber(isPrivate = true, tab = 5))
        assertEquals(5L, OutcomeLines.tabNumber(isPrivate = false, tab = 5))
    }
}

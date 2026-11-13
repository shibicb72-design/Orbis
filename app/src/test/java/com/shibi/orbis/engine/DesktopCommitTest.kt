package com.shibi.orbis.engine

import com.shibi.orbis.compat.PageSample
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopCommitTest {
    @Test
    fun desktopPageThenAMobileSiteSwitchesToMobileAndReloads() {
        assertEquals(DesktopCommit.Apply(desktop = false, reload = true), desktopAfterCommit(wanted = false, current = true, reloadedForLastCommit = false))
    }

    @Test
    fun mobilePageThenADesktopSiteSwitchesToDesktopAndReloads() {
        assertEquals(DesktopCommit.Apply(desktop = true, reload = true), desktopAfterCommit(wanted = true, current = false, reloadedForLastCommit = false))
    }

    @Test
    fun sameModeDoesNothing() {
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = true, current = true, reloadedForLastCommit = false))
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = false, current = false, reloadedForLastCommit = false))
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = false, current = false, reloadedForLastCommit = true))
    }

    @Test
    fun theReloadsOwnCommitNeverReloadsAgain() {
        assertEquals(DesktopCommit.Apply(desktop = true, reload = false), desktopAfterCommit(wanted = true, current = false, reloadedForLastCommit = true))
        assertEquals(DesktopCommit.Apply(desktop = false, reload = false), desktopAfterCommit(wanted = false, current = true, reloadedForLastCommit = true))
    }

    @Test
    fun noChoiceLeavesTheMode() {
        assertEquals(DesktopCommit.Leave, desktopAfterCommit(wanted = null, current = true, reloadedForLastCommit = false))
    }

    @Test
    fun onlyARedirectFromDesktopToMobileIsLoadedAfresh() {
        assertEquals(true, redirectNeedsFreshLoad(isRedirect = true, desktopBefore = true, desktopAfter = false, restartsSoFar = 0))
        assertEquals(false, redirectNeedsFreshLoad(isRedirect = true, desktopBefore = false, desktopAfter = true, restartsSoFar = 0))
        assertEquals(false, redirectNeedsFreshLoad(isRedirect = true, desktopBefore = true, desktopAfter = true, restartsSoFar = 0))
        assertEquals(false, redirectNeedsFreshLoad(isRedirect = false, desktopBefore = true, desktopAfter = false, restartsSoFar = 0))
    }

    @Test
    fun redirectRestartsStopAtTheLimit() {
        assertEquals(true, redirectNeedsFreshLoad(true, true, false, restartsSoFar = MAX_DESKTOP_REDIRECT_RESTARTS - 1))
        assertEquals(false, redirectNeedsFreshLoad(true, true, false, restartsSoFar = MAX_DESKTOP_REDIRECT_RESTARTS))
    }

    @Test
    fun compatSampleIsParsedAndCapped() {
        val heads = JSONArray()
        repeat(7) { heads.put("h$it" + "x".repeat(300)) }
        val msg =
            parseCompatMessage(
                JSONObject().put("type", "sample").put("title", "Unsupported browser").put("headlines", heads)
                    .put("text", "y".repeat(5000)).put("blockPath", true),
            )
        val sample = (msg as CompatMessage.Sample).sample
        assertEquals("Unsupported browser", sample.title)
        assertEquals(5, sample.headlines.size)
        assertEquals(200, sample.headlines[0].length)
        assertEquals(801, sample.visibleText.length)
        assertEquals(true, sample.blockPath)
    }

    @Test
    fun compatCleanAndUnknownMessages() {
        assertEquals(CompatMessage.Clean, parseCompatMessage(JSONObject().put("type", "clean")))
        assertNull(parseCompatMessage(JSONObject().put("type", "probe")))
        assertEquals(
            CompatMessage.Sample(PageSample(title = "", headlines = emptyList(), visibleText = "", blockPath = false)),
            parseCompatMessage(JSONObject().put("type", "sample")),
        )
    }
}

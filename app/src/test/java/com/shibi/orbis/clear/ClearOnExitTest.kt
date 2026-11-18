@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.shibi.orbis.clear

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryPrefs(override var enabled: Boolean = false, override var exceptions: Set<String> = emptySet()) : ClearOnExitPrefs

/** Every call into the shared targets and the clear-on-exit hooks, in one ordered list. */
private class Recorder : ClearTargets, ClearOnExitHooks {
    val calls = mutableListOf<String>()
    var ready = false
    var engineDelayMillis = 0L
    var engineResult = true
    var discardThrows = false
    var engineExceptions: Set<String>? = null

    override fun clearHistory() {
        calls += "history"
    }

    override fun closeNormalTabs() {
        calls += "closeNormalTabs"
    }

    override fun clearDownloadsList() {
        calls += "downloads"
    }

    override fun clearBookmarks() {
        calls += "bookmarks"
    }

    override fun clearPasswords() {
        calls += "passwords"
    }

    override fun hasPrivateTabs(): Boolean = false

    override suspend fun clearEngine(cookiesAndSiteData: Boolean, cache: Boolean): Boolean {
        calls += "manualEngine"
        return true
    }

    override suspend fun discardStoredTabs() {
        calls += "discardTabs"
        if (discardThrows) throw IOException("read-only")
    }

    override fun closeOpenTabs() {
        calls += "closeOpenTabs"
    }

    override fun restoreTabs() {
        calls += "restore"
        ready = true
    }

    override suspend fun clearEngine(exceptions: Set<String>): Boolean {
        calls += "engine"
        engineExceptions = exceptions
        if (engineDelayMillis > 0) delay(engineDelayMillis)
        return engineResult
    }

    fun count(name: String) = calls.count { it == name }
}

private class Setup(scope: TestScope, enabled: Boolean, exceptions: Set<String> = emptySet()) {
    val prefs = MemoryPrefs(enabled, exceptions)
    val recorder = Recorder()
    val logs = mutableListOf<String>()
    val clearOnExit = ClearOnExit(prefs, recorder, recorder, scope, log = { logs += it })
}

class ClearOnExitTest {
    @Test
    fun settingOffFreshStartClearsNothingAndRestoresTabs() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = false)
            s.clearOnExit.onAppStart()
            s.clearOnExit.onActivityCreated(hasSavedState = false)
            advanceUntilIdle()
            assertEquals(listOf("restore"), s.recorder.calls)
            assertTrue(s.recorder.ready)
            assertFalse(s.clearOnExit.wiping.value)
        }

    @Test
    fun settingOnFreshStartWipesOnceThenRestores() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true, exceptions = setOf("example.com"))
            var opened = false
            s.clearOnExit.onAppStart()
            // Nothing is read before the first activity decides.
            assertEquals(emptyList<String>(), s.recorder.calls)
            s.clearOnExit.onActivityCreated(hasSavedState = false)
            s.clearOnExit.afterWipe { opened = s.recorder.ready }
            assertTrue(s.clearOnExit.wiping.value)
            assertFalse(s.recorder.ready)
            advanceUntilIdle()
            assertEquals(1, s.recorder.count("history"))
            assertEquals(1, s.recorder.count("discardTabs"))
            assertEquals(1, s.recorder.count("engine"))
            assertEquals(setOf("example.com"), s.recorder.engineExceptions)
            assertEquals(0, s.recorder.count("bookmarks"))
            assertEquals(0, s.recorder.count("passwords"))
            assertEquals(0, s.recorder.count("downloads"))
            // The tab list is read once, after the engine, so ready comes only after the wipe.
            assertEquals("restore", s.recorder.calls.last())
            assertEquals(1, s.recorder.count("restore"))
            assertTrue(s.recorder.calls.indexOf("engine") < s.recorder.calls.indexOf("restore"))
            // A link or file that started Orbis is opened after the wipe, with the tabs ready.
            assertTrue(opened)
            assertFalse(s.clearOnExit.wiping.value)
            assertTrue(s.logs.contains("ClearOnExit: engine done"))
        }

    @Test
    fun settingOnRestoreAfterBackgroundKillWipesNothing() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            s.clearOnExit.onAppStart()
            s.clearOnExit.onActivityCreated(hasSavedState = true)
            advanceUntilIdle()
            assertEquals(listOf("restore"), s.recorder.calls)
        }

    @Test
    fun settingOnProcessSurvivedTheSwipeClosesLiveTabs() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = false)
            s.clearOnExit.onAppStart()
            s.clearOnExit.onActivityCreated(hasSavedState = false)
            s.prefs.enabled = true
            s.clearOnExit.onActivityDestroyed()
            advanceUntilIdle()
            s.recorder.calls.clear()
            s.clearOnExit.onActivityCreated(hasSavedState = false)
            // Closed before the activity can show them.
            assertEquals(listOf("closeOpenTabs", "history"), s.recorder.calls)
            advanceUntilIdle()
            assertEquals(listOf("closeOpenTabs", "history", "engine"), s.recorder.calls)
        }

    @Test
    fun engineSlowerThanTenSecondsStillMakesTabsReadyAndNextFreshStartClearsAgain() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            s.recorder.engineDelayMillis = 60_000L
            s.clearOnExit.onAppStart()
            s.clearOnExit.onActivityCreated(hasSavedState = false)
            runCurrent()
            advanceTimeBy(CLEAR_ON_EXIT_ENGINE_TIMEOUT_MILLIS - 1)
            runCurrent()
            assertFalse(s.recorder.ready)
            advanceTimeBy(2)
            runCurrent()
            assertTrue(s.recorder.ready)
            assertFalse(s.clearOnExit.wiping.value)
            assertTrue(s.logs.contains("ClearOnExit: unconfirmed"))
            // Fully closed again, the process lived on: the next fresh start wipes again.
            s.recorder.engineDelayMillis = 0L
            s.clearOnExit.onActivityDestroyed()
            s.clearOnExit.onActivityCreated(hasSavedState = false)
            advanceUntilIdle()
            assertEquals(2, s.recorder.count("engine"))
            assertEquals(1, s.recorder.count("restore"))
        }

    @Test
    fun secondFreshCreateDuringAWipeDoesNotStartAnother() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            s.recorder.engineDelayMillis = 1_000L
            s.clearOnExit.onAppStart()
            // The link router finishes before the browser activity is created.
            s.clearOnExit.onActivityCreated(hasSavedState = false)
            s.clearOnExit.onActivityDestroyed()
            s.clearOnExit.onActivityCreated(hasSavedState = false)
            advanceUntilIdle()
            assertEquals(1, s.recorder.count("engine"))
            assertEquals(1, s.recorder.count("restore"))
        }

    @Test
    fun storedTabsThatCannotBeDeletedAreOverwrittenBeforeTheRestore() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            s.recorder.discardThrows = true
            s.clearOnExit.onAppStart()
            s.clearOnExit.onActivityCreated(hasSavedState = false)
            advanceUntilIdle()
            assertEquals(listOf("history", "discardTabs", "engine", "closeOpenTabs", "restore"), s.recorder.calls)
            assertTrue(s.logs.contains("ClearOnExit: tabs failed: IOException"))
        }

    // The eight cases of feat08-3d, with the setting on. Each starts a new process unless it says otherwise. Activities
    // report as on Android 16 (scratch\feat08-3d\step2-signals.md); MainActivity never passes the Recents signal.

    /** True when anything of the wipe ran. */
    private fun Setup.wiped() = recorder.calls.any { it in setOf("history", "discardTabs", "engine", "closeOpenTabs") }

    /** Main started from the launcher with no saved state (rows 1 and 2). */
    private fun TestScope.launcherAfterFullClose(): Setup {
        val s = Setup(this, enabled = true)
        s.clearOnExit.onAppStart()
        s.clearOnExit.onActivityCreated(hasSavedState = false)
        advanceUntilIdle()
        return s
    }

    /** A link from another app: the router, then the browser activity. Returns whether the link was opened. */
    private fun TestScope.link(s: Setup, browserTaskInRecents: Boolean, mainHasSavedState: Boolean): Boolean {
        var opened = false
        s.clearOnExit.onActivityCreated(hasSavedState = false, browserTaskInRecents = browserTaskInRecents)
        s.clearOnExit.onActivityDestroyed()
        s.clearOnExit.onActivityCreated(hasSavedState = mainHasSavedState)
        s.clearOnExit.afterWipe { opened = s.recorder.ready }
        advanceUntilIdle()
        return opened
    }

    @Test
    fun row1SwipedThenLauncherWipes() =
        runTest(StandardTestDispatcher()) {
            assertTrue(launcherAfterFullClose().wiped())
        }

    @Test
    fun row2ForceStoppedThenLauncherWipes() =
        runTest(StandardTestDispatcher()) {
            val s = launcherAfterFullClose()
            assertEquals(listOf("history", "discardTabs", "engine", "restore"), s.recorder.calls)
        }

    @Test
    fun row3BackgroundKillThenRecentsWipesNothing() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            s.clearOnExit.onAppStart()
            s.clearOnExit.onActivityCreated(hasSavedState = true)
            advanceUntilIdle()
            assertEquals(listOf("restore"), s.recorder.calls)
        }

    @Test
    fun row4BackgroundKillThenLinkWipesNothingAndOpensTheLinkWithTheOldTabs() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            s.clearOnExit.onAppStart()
            val opened = link(s, browserTaskInRecents = true, mainHasSavedState = true)
            assertEquals(listOf("restore"), s.recorder.calls)
            assertTrue(opened)
            assertFalse(s.clearOnExit.wiping.value)
        }

    @Test
    fun row5BackgroundKillThenCustomTabWipesNothing() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            var shown = false
            s.clearOnExit.onAppStart()
            // The router, in the browser's task; then the Custom Tab, in a task of its own, with no saved state.
            s.clearOnExit.onActivityCreated(hasSavedState = false, browserTaskInRecents = true)
            s.clearOnExit.onActivityDestroyed()
            s.clearOnExit.onActivityCreated(hasSavedState = false, browserTaskInRecents = true)
            s.clearOnExit.afterWipe { shown = true }
            advanceUntilIdle()
            assertEquals(listOf("restore"), s.recorder.calls)
            assertTrue(shown)
        }

    @Test
    fun row6SwipedThenLinkWipesThenOpensTheLink() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            s.clearOnExit.onAppStart()
            val opened = link(s, browserTaskInRecents = false, mainHasSavedState = false)
            assertEquals(listOf("history", "discardTabs", "engine", "restore"), s.recorder.calls)
            // Opened after the wipe, with the (empty) tab list ready.
            assertTrue(opened)
        }

    @Test
    fun row7ForceStoppedThenLinkWipesThenOpensTheLink() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            s.clearOnExit.onAppStart()
            val opened = link(s, browserTaskInRecents = false, mainHasSavedState = false)
            assertEquals(1, s.recorder.count("engine"))
            assertEquals(1, s.recorder.count("restore"))
            assertTrue(opened)
        }

    @Test
    fun row8DontKeepActivitiesThenLinkWipesNothing() =
        runTest(StandardTestDispatcher()) {
            val s = Setup(this, enabled = true)
            s.clearOnExit.onAppStart()
            s.clearOnExit.onActivityCreated(hasSavedState = true)
            advanceUntilIdle()
            // Home with "Don't keep activities": the browser activity is destroyed, the process and the task stay.
            s.clearOnExit.onActivityDestroyed()
            val opened = link(s, browserTaskInRecents = true, mainHasSavedState = true)
            assertEquals(listOf("restore"), s.recorder.calls)
            assertTrue(opened)
        }

    @Test
    fun exceptionDomainsAreNormalised() {
        assertEquals("example.com", clearOnExitDomain(" https://www.Example.com/path?q=1 "))
        assertEquals("mail.example.co.uk", clearOnExitDomain("mail.example.co.uk"))
        assertNull(clearOnExitDomain("localhost"))
        assertNull(clearOnExitDomain("192.168.0.1"))
        assertNull(clearOnExitDomain("exa mple.com"))
        val many = (1..250).map { "site$it.com" } + "not a domain"
        assertEquals(CLEAR_ON_EXIT_MAX_EXCEPTIONS, cleanExceptions(many).size)
    }
}

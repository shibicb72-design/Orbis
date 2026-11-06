package com.shibi.orbis.clear

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Records every call, in order. Throwing and slow behaviour is switched on per test. */
private class RecordingTargets : ClearTargets {
    val calls = mutableListOf<String>()
    var privateTabs = false
    var privateCheckThrows = false
    var privateChecks = 0
    var engineResult = true
    var engineDelayMillis = 0L
    var engineThrows: Exception? = null
    val throwOn = mutableMapOf<String, Exception>()

    private fun record(name: String) {
        calls += name
        throwOn[name]?.let { throw it }
    }

    override fun clearHistory() = record("history")
    override fun closeNormalTabs() = record("tabs")
    override fun clearDownloadsList() = record("downloads")
    override fun clearBookmarks() = record("bookmarks")

    override fun hasPrivateTabs(): Boolean {
        privateChecks++
        if (privateCheckThrows) throw IllegalStateException("tabs unavailable")
        return privateTabs
    }

    override suspend fun clearEngine(cookiesAndSiteData: Boolean, cache: Boolean): Boolean {
        calls += "engine(cookies=$cookiesAndSiteData,cache=$cache)"
        if (engineDelayMillis > 0) delay(engineDelayMillis)
        engineThrows?.let { throw it }
        return engineResult
    }
}

private fun selectionOf(mask: Int) =
    ClearSelection(
        history = mask and 1 != 0,
        cookiesAndSiteData = mask and 2 != 0,
        cache = mask and 4 != 0,
        downloadsList = mask and 8 != 0,
        openTabs = mask and 16 != 0,
        bookmarks = mask and 32 != 0,
    )

/** The calls a selection must produce, written out independently of the implementation: fixed order, engine last. */
private fun expectedCalls(s: ClearSelection): List<String> =
    buildList {
        if (s.openTabs) add("tabs")
        if (s.history) add("history")
        if (s.downloadsList) add("downloads")
        if (s.bookmarks) add("bookmarks")
        if (s.cookiesAndSiteData || s.cache) add("engine(cookies=${s.cookiesAndSiteData},cache=${s.cache})")
    }

class BrowsingDataClearerTest {
    private val logs = mutableListOf<String>()

    private fun clearer(targets: ClearTargets, timeout: Long = 30_000L) =
        BrowsingDataClearer(targets, log = { logs += it }, engineTimeoutMillis = timeout)

    // ---- the selection ----

    @Test
    fun theDefaultsAreHistoryCookiesAndCacheOnAndTheRestOff() {
        val d = ClearSelection()
        assertTrue(d.history)
        assertTrue(d.cookiesAndSiteData)
        assertTrue(d.cache)
        assertFalse(d.downloadsList)
        assertFalse(d.openTabs)
        assertFalse(d.bookmarks)
        assertFalse(d.isEmpty)
    }

    @Test
    fun bookmarksNeverDefaultToOn() {
        assertFalse(ClearSelection().bookmarks)
        assertFalse(ClearSelection(history = false).bookmarks)
        assertFalse(ClearSelection(openTabs = true, downloadsList = true).bookmarks)
    }

    @Test
    fun categoriesListTheTickedOnesInPanelOrder() {
        assertEquals(
            listOf(ClearCategory.HISTORY, ClearCategory.COOKIES_AND_SITE_DATA, ClearCategory.CACHE),
            ClearSelection().categories,
        )
        assertEquals(
            ClearCategory.entries,
            ClearSelection(true, true, true, true, true, true).categories,
        )
        assertTrue(selectionOf(0).categories.isEmpty())
        assertTrue(selectionOf(0).isEmpty)
    }

    // ---- pure helpers, every combination ----

    @Test
    fun needsEngineIsTrueForCookiesOrCacheOnly() {
        for (mask in 0 until 64) {
            val s = selectionOf(mask)
            assertEquals("mask $mask", s.cookiesAndSiteData || s.cache, needsEngine(s))
        }
    }

    @Test
    fun blockedAndCanSubmitForEveryCombination() {
        for (mask in 0 until 64) {
            val s = selectionOf(mask)
            for (privateTabs in listOf(false, true)) {
                val blocked = (s.cookiesAndSiteData || s.cache) && privateTabs
                assertEquals("mask $mask private $privateTabs", blocked, blockedByPrivateTabs(s, privateTabs))
                for (running in listOf(false, true)) {
                    val expected = mask != 0 && !blocked && !running
                    assertEquals("mask $mask private $privateTabs running $running", expected, canSubmit(s, privateTabs, running))
                }
            }
        }
    }

    @Test
    fun untickingCookiesAndCacheReEnablesTheRestWhilePrivateTabsAreOpen() {
        val all = ClearSelection(true, true, true, true, true, false)
        assertFalse(canSubmit(all, hasPrivateTabs = true, running = false))
        val rest = all.copy(cookiesAndSiteData = false, cache = false)
        assertTrue(canSubmit(rest, hasPrivateTabs = true, running = false))
        // Either one alone still needs the engine.
        assertFalse(canSubmit(rest.copy(cache = true), hasPrivateTabs = true, running = false))
        assertFalse(canSubmit(rest.copy(cookiesAndSiteData = true), hasPrivateTabs = true, running = false))
    }

    // ---- every combination, order and arguments ----

    @Test
    fun everyCombinationCallsExactlyTheSelectedTargetsInFixedOrderWithNoPrivateTab() = runTest {
        for (mask in 0 until 64) {
            val s = selectionOf(mask)
            val t = RecordingTargets()
            val outcome = clearer(t).run(s)
            assertEquals("mask $mask", expectedCalls(s), t.calls)
            assertEquals("mask $mask", ClearOutcome.Done, outcome)
        }
    }

    @Test
    fun bookmarksAreTouchedOnlyWhenSelected() = runTest {
        for (mask in 0 until 64) {
            val s = selectionOf(mask)
            for (privateTabs in listOf(false, true)) {
                val t = RecordingTargets().also { it.privateTabs = privateTabs }
                clearer(t).run(s)
                assertEquals("mask $mask private $privateTabs", s.bookmarks && !(needsEngine(s) && privateTabs), "bookmarks" in t.calls)
            }
        }
    }

    @Test
    fun theEngineGetsExactlyTheTwoBooleans() = runTest {
        for ((cookies, cache) in listOf(true to true, true to false, false to true)) {
            val t = RecordingTargets()
            clearer(t).run(ClearSelection(history = false, cookiesAndSiteData = cookies, cache = cache))
            assertEquals(listOf("engine(cookies=$cookies,cache=$cache)"), t.calls)
        }
    }

    @Test
    fun theEngineIsNotCalledWhenNeitherCookiesNorCacheIsSelected() = runTest {
        val t = RecordingTargets()
        clearer(t).run(ClearSelection(true, false, false, true, true, true))
        assertEquals(listOf("tabs", "history", "downloads", "bookmarks"), t.calls)
    }

    @Test
    fun anEmptySelectionDoesNothing() = runTest {
        val t = RecordingTargets()
        assertEquals(ClearOutcome.Done, clearer(t).run(selectionOf(0)))
        assertTrue(t.calls.isEmpty())
        assertEquals(0, t.privateChecks)
    }

    @Test
    fun tabsAreClosedBeforeAnythingElseAndTheEngineIsLast() = runTest {
        val t = RecordingTargets()
        clearer(t).run(ClearSelection(true, true, true, true, true, true))
        assertEquals(
            listOf("tabs", "history", "downloads", "bookmarks", "engine(cookies=true,cache=true)"),
            t.calls,
        )
    }

    // ---- blocked ----

    @Test
    fun withAPrivateTabOpenEveryEngineSelectionClearsNothingAtAll() = runTest {
        for (mask in 0 until 64) {
            val s = selectionOf(mask)
            if (!needsEngine(s)) continue
            val t = RecordingTargets().also { it.privateTabs = true }
            val outcome = clearer(t).run(s)
            assertEquals("mask $mask", ClearOutcome.Blocked, outcome)
            assertTrue("mask $mask: ${t.calls}", t.calls.isEmpty())
        }
    }

    @Test
    fun withAPrivateTabOpenASelectionWithoutCookiesAndCacheStillClears() = runTest {
        for (mask in 1 until 64) {
            val s = selectionOf(mask)
            if (needsEngine(s)) continue
            val t = RecordingTargets().also { it.privateTabs = true }
            assertEquals("mask $mask", ClearOutcome.Done, clearer(t).run(s))
            assertEquals("mask $mask", expectedCalls(s), t.calls)
        }
    }

    @Test
    fun thePrivateTabsAreCheckedAtRunTimeNotWhenTheSelectionWasMade() = runTest {
        val t = RecordingTargets()
        val c = clearer(t)
        val s = ClearSelection()
        assertEquals(ClearOutcome.Done, c.run(s))
        t.calls.clear()
        t.privateTabs = true
        assertEquals(ClearOutcome.Blocked, c.run(s))
        assertTrue(t.calls.isEmpty())
        assertTrue(t.privateChecks >= 2)
    }

    @Test
    fun aPrivateTabCheckThatFailsCountsAsAPrivateTabBeingOpen() = runTest {
        val t = RecordingTargets().also { it.privateCheckThrows = true }
        assertEquals(ClearOutcome.Blocked, clearer(t).run(ClearSelection()))
        assertTrue(t.calls.isEmpty())
        // Without the engine the check is not needed at all.
        assertEquals(ClearOutcome.Done, clearer(t).run(ClearSelection(history = true, cookiesAndSiteData = false, cache = false)))
        assertEquals(listOf("history"), t.calls)
    }

    // ---- failures ----

    @Test
    fun oneCategoryThrowingDoesNotStopTheOthers() = runTest {
        val categories =
            mapOf(
                "tabs" to ClearCategory.OPEN_TABS,
                "history" to ClearCategory.HISTORY,
                "downloads" to ClearCategory.DOWNLOADS_LIST,
                "bookmarks" to ClearCategory.BOOKMARKS,
            )
        for ((name, category) in categories) {
            val t = RecordingTargets().also { it.throwOn[name] = IllegalStateException("boom") }
            val s = ClearSelection(true, true, true, true, true, true)
            val outcome = clearer(t).run(s)
            assertEquals(name, expectedCalls(s), t.calls)
            assertEquals(name, ClearOutcome.Incomplete(failed = setOf(category), unconfirmed = emptySet()), outcome)
        }
    }

    @Test
    fun severalFailuresAreAllReported() = runTest {
        val t = RecordingTargets()
        t.throwOn["history"] = java.io.IOException()
        t.throwOn["bookmarks"] = RuntimeException()
        val outcome = clearer(t).run(ClearSelection(true, true, true, false, false, true))
        assertEquals(
            ClearOutcome.Incomplete(setOf(ClearCategory.HISTORY, ClearCategory.BOOKMARKS), emptySet()),
            outcome,
        )
        assertEquals(listOf("history", "bookmarks", "engine(cookies=true,cache=true)"), t.calls)
    }

    @Test
    fun anEngineThatThrowsFailsBothEngineCategories() = runTest {
        val t = RecordingTargets().also { it.engineThrows = IllegalStateException("gecko") }
        val outcome = clearer(t).run(ClearSelection())
        assertEquals(
            ClearOutcome.Incomplete(failed = setOf(ClearCategory.COOKIES_AND_SITE_DATA, ClearCategory.CACHE), unconfirmed = emptySet()),
            outcome,
        )
        // History was still cleared.
        assertTrue("history" in t.calls)
    }

    @Test
    fun anEngineThatReportsFailureIsUnconfirmedOnlyForTheSelectedEngineCategories() = runTest {
        val t = RecordingTargets().also { it.engineResult = false }
        assertEquals(
            ClearOutcome.Incomplete(failed = emptySet(), unconfirmed = setOf(ClearCategory.COOKIES_AND_SITE_DATA)),
            clearer(t).run(ClearSelection(history = false, cookiesAndSiteData = true, cache = false)),
        )
        assertEquals(
            ClearOutcome.Incomplete(failed = emptySet(), unconfirmed = setOf(ClearCategory.CACHE)),
            clearer(t).run(ClearSelection(history = false, cookiesAndSiteData = false, cache = true)),
        )
        assertEquals(
            ClearOutcome.Incomplete(failed = emptySet(), unconfirmed = setOf(ClearCategory.COOKIES_AND_SITE_DATA, ClearCategory.CACHE)),
            clearer(t).run(ClearSelection()),
        )
    }

    @Test
    fun anEngineThatNeverAnswersIsUnconfirmedAfterTheTimeoutAndNeverHangs() = runTest {
        val t = RecordingTargets().also { it.engineDelayMillis = Long.MAX_VALUE / 4 }
        val outcome = clearer(t).run(ClearSelection())
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(
            ClearOutcome.Incomplete(failed = emptySet(), unconfirmed = setOf(ClearCategory.COOKIES_AND_SITE_DATA, ClearCategory.CACHE)),
            outcome,
        )
        assertTrue("history" in t.calls)
    }

    @Test
    fun anEngineThatAnswersInTimeIsConfirmed() = runTest {
        val t = RecordingTargets().also { it.engineDelayMillis = 29_000L }
        assertEquals(ClearOutcome.Done, clearer(t).run(ClearSelection()))
        assertEquals(29_000L, testScheduler.currentTime)
    }

    @Test
    fun cancellationIsNeverSwallowed() = runTest {
        val t = RecordingTargets().also { it.throwOn["history"] = CancellationException("stop") }
        try {
            clearer(t).run(ClearSelection())
            fail("cancellation was swallowed")
        } catch (e: CancellationException) {
            // expected
        }
        // And nothing after it ran.
        assertFalse(t.calls.any { it.startsWith("engine") })
    }

    @Test
    fun anEngineCancellationIsNeverSwallowedEither() = runTest {
        val t = RecordingTargets().also { it.engineThrows = CancellationException("stop") }
        try {
            clearer(t).run(ClearSelection())
            fail("cancellation was swallowed")
        } catch (e: CancellationException) {
            // expected
        }
    }

    // ---- logging ----

    @Test
    fun logsNameCategoriesAndOutcomesOnly() = runTest {
        val t = RecordingTargets()
        t.throwOn["history"] = IllegalStateException("https://secret.example/path?token=abc")
        t.engineResult = false
        clearer(t).run(ClearSelection(true, true, true, true, true, true))
        clearer(RecordingTargets().also { it.privateTabs = true }).run(ClearSelection())
        assertTrue(logs.isNotEmpty())
        for (line in logs) {
            assertFalse(line, line.contains("http"))
            assertFalse(line, line.contains("secret"))
            assertFalse(line, line.contains("token"))
        }
        assertTrue(logs.any { it == "clear failed: HISTORY: IllegalStateException" })
        assertTrue(logs.any { it == "clear blocked: private tabs open" })
    }
}

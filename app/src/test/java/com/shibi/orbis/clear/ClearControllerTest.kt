package com.shibi.orbis.clear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class GatedTargets : ClearTargets {
    val gate = CompletableDeferred<Boolean>()
    var historyCalls = 0
    var engineCalls = 0
    var privateTabs = false

    override fun clearHistory() { historyCalls++ }
    override fun closeNormalTabs() = Unit
    override fun clearDownloadsList() = Unit
    override fun clearBookmarks() = Unit
    override fun hasPrivateTabs() = privateTabs

    override suspend fun clearEngine(cookiesAndSiteData: Boolean, cache: Boolean): Boolean {
        engineCalls++
        return gate.await()
    }
}

class ClearControllerTest {
    @Test
    fun startsIdleRunsThenFinishesWithTheOutcome() = runTest {
        val targets = GatedTargets()
        val c = ClearController(BrowsingDataClearer(targets), this)
        assertEquals(ClearState.Idle, c.state.value)

        assertTrue(c.start(ClearSelection()))
        assertEquals(ClearState.Running, c.state.value)
        runCurrent()
        // Waiting for the engine.
        assertEquals(ClearState.Running, c.state.value)
        assertEquals(1, targets.historyCalls)

        targets.gate.complete(true)
        advanceUntilIdle()
        assertEquals(ClearState.Finished(ClearOutcome.Done), c.state.value)
    }

    @Test
    fun aSecondStartWhileRunningIsIgnored() = runTest {
        val targets = GatedTargets()
        val c = ClearController(BrowsingDataClearer(targets), this)
        assertTrue(c.start(ClearSelection()))
        runCurrent()
        assertFalse(c.start(ClearSelection(history = true, cookiesAndSiteData = false, cache = false, bookmarks = true)))
        assertFalse(c.start(ClearSelection()))
        runCurrent()
        assertEquals(1, targets.historyCalls)
        assertEquals(1, targets.engineCalls)
        assertEquals(ClearState.Running, c.state.value)

        targets.gate.complete(true)
        advanceUntilIdle()
        assertEquals(1, targets.historyCalls)
        assertEquals(1, targets.engineCalls)
        assertEquals(ClearState.Finished(ClearOutcome.Done), c.state.value)
    }

    @Test
    fun aBlockedRequestFinishesAsBlockedAndClearsNothing() = runTest {
        val targets = GatedTargets().also { it.privateTabs = true }
        val c = ClearController(BrowsingDataClearer(targets), this)
        assertTrue(c.start(ClearSelection()))
        advanceUntilIdle()
        assertEquals(ClearState.Finished(ClearOutcome.Blocked), c.state.value)
        assertEquals(0, targets.historyCalls)
        assertEquals(0, targets.engineCalls)
    }

    @Test
    fun anUnconfirmedEngineFinishesAsIncomplete() = runTest {
        val targets = GatedTargets().also { it.gate.complete(false) }
        val c = ClearController(BrowsingDataClearer(targets), this)
        c.start(ClearSelection(history = false))
        advanceUntilIdle()
        assertEquals(
            ClearState.Finished(
                ClearOutcome.Incomplete(emptySet(), setOf(ClearCategory.COOKIES_AND_SITE_DATA, ClearCategory.CACHE)),
            ),
            c.state.value,
        )
    }

    @Test
    fun dismissingTheResultGoesBackToIdleAndAllowsANewStart() = runTest {
        val targets = GatedTargets().also { it.gate.complete(true) }
        val c = ClearController(BrowsingDataClearer(targets), this)
        c.start(ClearSelection())
        advanceUntilIdle()
        assertTrue(c.state.value is ClearState.Finished)
        c.dismissResult()
        assertEquals(ClearState.Idle, c.state.value)

        assertTrue(c.start(ClearSelection()))
        advanceUntilIdle()
        assertEquals(2, targets.historyCalls)
    }

    @Test
    fun dismissingWhileRunningChangesNothing() = runTest {
        val targets = GatedTargets()
        val c = ClearController(BrowsingDataClearer(targets), this)
        c.start(ClearSelection())
        runCurrent()
        c.dismissResult()
        assertEquals(ClearState.Running, c.state.value)
        targets.gate.complete(true)
        advanceUntilIdle()
        assertEquals(ClearState.Finished(ClearOutcome.Done), c.state.value)
    }

    @Test
    fun aFinishedResultStillAllowsANewStartWithoutDismissing() = runTest {
        val targets = GatedTargets().also { it.gate.complete(true) }
        val c = ClearController(BrowsingDataClearer(targets), this)
        c.start(ClearSelection())
        advanceUntilIdle()
        assertTrue(c.start(ClearSelection(history = true, cookiesAndSiteData = false, cache = false)))
        assertEquals(ClearState.Running, c.state.value)
        advanceUntilIdle()
        assertEquals(2, targets.historyCalls)
        assertEquals(1, targets.engineCalls)
    }
}

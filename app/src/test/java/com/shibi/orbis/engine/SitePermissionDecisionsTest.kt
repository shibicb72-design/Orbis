package com.shibi.orbis.engine

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class MemoryStore(
    var saved: List<SitePermissionDecision> = emptyList(),
    var failLoad: Boolean = false,
    var failSave: Boolean = false,
) : SitePermissionStore {
    var saves = 0

    override fun load(): List<SitePermissionDecision> {
        if (failLoad) throw IllegalStateException("simulated load failure")
        return saved
    }

    override fun save(decisions: List<SitePermissionDecision>) {
        if (failSave) throw IOException("simulated disk full")
        saved = decisions
        saves++
    }
}

class SitePermissionDecisionsTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000L
    private val logs = CopyOnWriteArrayList<String>()

    private val loc = SitePermissionKey(SitePermissionKind.LOCATION, "https://a.example")
    private val cam = SitePermissionKey(SitePermissionKind.CAMERA, "https://a.example")
    private val locB = SitePermissionKey(SitePermissionKind.LOCATION, "https://b.example")

    private fun TestScope.decisions(store: SitePermissionStore): SitePermissionDecisions {
        val io = StandardTestDispatcher(testScheduler)
        return SitePermissionDecisions(store, CoroutineScope(io), io, { clock }, { logs += it })
    }

    private suspend fun TestScope.loaded(store: SitePermissionStore): SitePermissionDecisions {
        val d = decisions(store)
        d.load()
        advanceUntilIdle()
        return d
    }

    @Test
    fun nothingKnownMeansNoDecision() = runTest {
        val d = loaded(MemoryStore())
        assertNull(d.lookup(loc))
        assertTrue(d.entries.value.isEmpty())
    }

    @Test
    fun rememberedDecisionSurvivesAReloadThroughTheStore() = runTest {
        val store = MemoryStore()
        val first = loaded(store)
        first.record(loc, allow = true, remember = true)
        first.record(cam, allow = false, remember = true)
        advanceUntilIdle()

        val second = loaded(store)
        assertEquals(true, second.lookup(loc))
        assertEquals(false, second.lookup(cam))
        assertTrue(second.entries.value.none { it.session })
    }

    @Test
    fun rememberedDecisionSurvivesAReloadThroughTheRealFile() = runTest {
        val file = File(tmp.root, "site_permissions.json")
        val first = loaded(FileSitePermissionStore(file))
        first.record(loc, allow = true, remember = true)
        advanceUntilIdle()
        assertTrue(file.isFile)
        assertFalse(File(tmp.root, "site_permissions.json.tmp").exists())

        val second = loaded(FileSitePermissionStore(file))
        assertEquals(true, second.lookup(loc))
    }

    @Test
    fun sessionDecisionDoesNotSurviveAReload() = runTest {
        val store = MemoryStore()
        val first = loaded(store)
        first.record(loc, allow = true, remember = false)
        advanceUntilIdle()
        assertEquals(true, first.lookup(loc))
        assertEquals(0, store.saves)

        val second = loaded(store)
        assertNull(second.lookup(loc))
    }

    @Test
    fun sessionDecisionExpiresAfterTheTtl() = runTest {
        val d = loaded(MemoryStore())
        d.record(loc, allow = true, remember = false)
        clock += SitePermissionDecisions.SESSION_TTL_MS - 1
        assertEquals(true, d.lookup(loc))
        assertEquals(1, d.entries.value.size)
        clock += 1
        assertNull(d.lookup(loc))
        assertTrue(d.entries.value.isEmpty())
    }

    @Test
    fun purgeExpiredRefreshesTheEntryList() = runTest {
        val d = loaded(MemoryStore())
        d.record(loc, allow = true, remember = false)
        d.record(cam, allow = true, remember = true)
        clock += SitePermissionDecisions.SESSION_TTL_MS
        d.purgeExpired()
        assertEquals(listOf(cam), d.entries.value.map { it.key })
    }

    @Test
    fun rememberedDecisionDoesNotExpire() = runTest {
        val d = loaded(MemoryStore())
        d.record(loc, allow = false, remember = true)
        clock += SitePermissionDecisions.SESSION_TTL_MS * 100
        assertEquals(false, d.lookup(loc))
    }

    @Test
    fun newerSessionDecisionReplacesRememberedOne() = runTest {
        val store = MemoryStore()
        val d = loaded(store)
        d.record(loc, allow = true, remember = true)
        advanceUntilIdle()
        d.record(loc, allow = false, remember = false)
        advanceUntilIdle()

        assertEquals(false, d.lookup(loc))
        val entry = d.entries.value.single()
        assertTrue(entry.session)
        assertFalse(entry.allow)
        // The remembered tier no longer holds the old answer.
        assertTrue(store.saved.isEmpty())
        assertNull(loaded(store).lookup(loc))
    }

    @Test
    fun newerRememberedDecisionReplacesSessionOne() = runTest {
        val store = MemoryStore()
        val d = loaded(store)
        d.record(loc, allow = false, remember = false)
        d.record(loc, allow = true, remember = true)
        advanceUntilIdle()

        val entry = d.entries.value.single()
        assertFalse(entry.session)
        assertTrue(entry.allow)
        // The old session answer must not come back once it would have expired or shadow the new one.
        clock += SitePermissionDecisions.SESSION_TTL_MS * 2
        assertEquals(true, d.lookup(loc))
        assertEquals(listOf(SitePermissionDecision(loc, true)), store.saved)
    }

    @Test
    fun laterDecisionOfTheSameTierReplacesTheEarlierOne() = runTest {
        val d = loaded(MemoryStore())
        d.record(loc, allow = true, remember = true)
        d.record(loc, allow = false, remember = true)
        assertEquals(false, d.lookup(loc))
        assertEquals(1, d.entries.value.size)
    }

    @Test
    fun removeClearsBothTiers() = runTest {
        val store = MemoryStore()
        val d = loaded(store)
        d.record(loc, allow = true, remember = true)
        d.record(cam, allow = true, remember = false)
        advanceUntilIdle()

        d.remove(loc)
        d.remove(cam)
        advanceUntilIdle()

        assertNull(d.lookup(loc))
        assertNull(d.lookup(cam))
        assertTrue(d.entries.value.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertNull(loaded(store).lookup(loc))
    }

    @Test
    fun removingAnUnknownKeyDoesNothing() = runTest {
        val store = MemoryStore()
        val d = loaded(store)
        d.remove(loc)
        advanceUntilIdle()
        assertEquals(0, store.saves)
    }

    @Test
    fun thirdPartyDecisionNeverAnswersForFirstPartyOrAnotherEmbedder() = runTest {
        val d = loaded(MemoryStore())
        val frameOnA = SitePermissionKey(SitePermissionKind.LOCATION, "https://embed.example", embedder = "https://a.example")
        d.record(frameOnA, allow = true, remember = true)

        assertEquals(true, d.lookup(frameOnA))
        // The embedded site on its own.
        assertNull(d.lookup(SitePermissionKey(SitePermissionKind.LOCATION, "https://embed.example")))
        // The same frame embedded elsewhere.
        assertNull(d.lookup(frameOnA.copy(embedder = "https://b.example")))
        // The embedding page asking for itself.
        assertNull(d.lookup(SitePermissionKey(SitePermissionKind.LOCATION, "https://a.example")))
        // Another kind.
        assertNull(d.lookup(frameOnA.copy(kind = SitePermissionKind.CAMERA)))
    }

    @Test
    fun firstPartyDecisionDoesNotAnswerForAnEmbeddedFrameOfTheSameOrigin() = runTest {
        val d = loaded(MemoryStore())
        d.record(loc, allow = true, remember = true)
        assertNull(d.lookup(loc.copy(origin = "https://embed.example", embedder = "https://a.example")))
        assertNull(d.lookup(loc.copy(embedder = "https://b.example")))
    }

    @Test
    fun entriesAreSortedByOrigin() = runTest {
        val d = loaded(MemoryStore())
        d.record(locB, allow = true, remember = true)
        d.record(loc, allow = true, remember = false)
        d.record(cam, allow = true, remember = true)
        assertEquals(
            listOf(cam, loc, locB),
            d.entries.value.map { it.key },
        )
    }

    @Test
    fun corruptFileLoadsAsEmptyAndTheNextSaveRepairsIt() = runTest {
        val file = File(tmp.root, "site_permissions.json")
        file.writeText("{\"version\":1,\"entries\":[{\"kind\":", Charsets.UTF_8)
        val d = loaded(FileSitePermissionStore(file))
        assertTrue(d.entries.value.isEmpty())
        d.record(loc, allow = true, remember = true)
        advanceUntilIdle()
        assertEquals(true, loaded(FileSitePermissionStore(file)).lookup(loc))
    }

    @Test
    fun aStoreThatThrowsOnLoadIsSurvived() = runTest {
        val store = MemoryStore(failLoad = true)
        val d = loaded(store)
        assertTrue(d.entries.value.isEmpty())
        store.failLoad = false
        d.record(loc, allow = true, remember = true)
        advanceUntilIdle()
        assertEquals(listOf(SitePermissionDecision(loc, true)), store.saved)
        assertTrue(logs.any { it.contains("IllegalStateException") })
    }

    @Test
    fun aStoreThatThrowsOnSaveIsSurvivedAndLogsTheClassOnly() = runTest {
        val store = MemoryStore(failSave = true)
        val d = loaded(store)
        d.record(loc, allow = true, remember = true)
        advanceUntilIdle()
        // Still in force in memory.
        assertEquals(true, d.lookup(loc))
        assertTrue(logs.any { it.contains("IOException") })
        assertTrue(logs.none { it.contains("a.example") || it.contains("disk full") })
    }

    @Test
    fun decisionMadeWhileLoadingNeverWipesTheFile() = runTest {
        val store = MemoryStore(saved = listOf(SitePermissionDecision(locB, true)))
        val d = decisions(store)
        d.load()
        // load() has not run yet: this record happens first.
        d.record(loc, allow = false, remember = true)
        advanceUntilIdle()

        assertEquals(true, d.lookup(locB))
        assertEquals(false, d.lookup(loc))
        assertEquals(setOf(loc, locB), store.saved.map { it.key }.toSet())
    }

    @Test
    fun aDecisionMadeWhileLoadingWinsOverTheFilesOlderAnswer() = runTest {
        val store = MemoryStore(saved = listOf(SitePermissionDecision(loc, true)))
        val d = decisions(store)
        d.load()
        d.record(loc, allow = false, remember = false)
        advanceUntilIdle()
        assertEquals(false, d.lookup(loc))
    }

    @Test
    fun aRemovalMadeWhileLoadingIsNotUndoneByTheFile() = runTest {
        val store = MemoryStore(saved = listOf(SitePermissionDecision(loc, true), SitePermissionDecision(locB, true)))
        val d = decisions(store)
        d.load()
        d.remove(loc)
        advanceUntilIdle()
        assertNull(d.lookup(loc))
        assertEquals(true, d.lookup(locB))
    }

    @Test
    fun storeIsCapped() = runTest {
        val d = loaded(MemoryStore())
        repeat(SitePermissionCodec.MAX_ENTRIES + 5) { i ->
            d.record(SitePermissionKey(SitePermissionKind.LOCATION, "https://site$i.example"), true, true)
        }
        assertEquals(SitePermissionCodec.MAX_ENTRIES, d.entries.value.size)
        // The eldest went first.
        assertNull(d.lookup(SitePermissionKey(SitePermissionKind.LOCATION, "https://site0.example")))
        assertEquals(
            true,
            d.lookup(SitePermissionKey(SitePermissionKind.LOCATION, "https://site${SitePermissionCodec.MAX_ENTRIES + 4}.example")),
        )
    }
}

package com.shibi.orbis.stats

import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What a stored memory sample can hold, the 30-day drop, the file, and deleting it. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 24L * 60 * 60 * 1000
    private var now = 100 * day

    private fun TestScope.history(file: File = File(tmp.root, "memory_history.json")): MemoryHistory {
        val d = StandardTestDispatcher(testScheduler)
        return MemoryHistory(file, CoroutineScope(d + SupervisorJob()), d, clock = { now })
    }

    private fun sample(at: Long, total: Long = 500_000L) = MemorySample(at, total, 200_000L, 250_000L, 50_000L, normalTabs = 6, awakeNormalTabs = 3)

    @Test
    fun aSampleCanHoldNumbersOnly() {
        // Fails as soon as anyone adds a field that could carry a title, an address, a host or a tab id.
        val fields = MemorySample::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertEquals(
            setOf("at", "totalKb", "appKb", "webKb", "otherKb", "normalTabs", "awakeNormalTabs"),
            fields.map { it.name }.toSet(),
        )
        assertTrue(fields.all { it.type == Long::class.javaPrimitiveType || it.type == Int::class.javaPrimitiveType })
        val stored = JSONObject(encodeMemorySamples(listOf(sample(now)))).getJSONArray("samples").getJSONObject(0)
        assertEquals(MEMORY_SAMPLE_KEYS, stored.keys().asSequence().toSet())
        assertTrue(MEMORY_SAMPLE_KEYS.all { stored.get(it) is Number })
    }

    @Test
    fun theFileHoldsNumbersOnlyAndSurvivesARestart() = runTest {
        val f = File(tmp.root, "memory_history.json")
        val a = history(f)
        a.load(); advanceUntilIdle()
        a.add(sample(now - 1_000))
        a.add(sample(now))
        advanceUntilIdle()
        val root = JSONObject(f.readText())
        assertEquals(setOf("version", "samples"), root.keys().asSequence().toSet())
        val stored = root.getJSONArray("samples")
        assertEquals(2, stored.length())
        for (i in 0 until stored.length()) {
            val o = stored.getJSONObject(i)
            assertEquals(MEMORY_SAMPLE_KEYS, o.keys().asSequence().toSet())
            assertTrue(MEMORY_SAMPLE_KEYS.all { o.get(it) is Number })
        }
        val b = history(f)
        b.load(); advanceUntilIdle()
        assertEquals(listOf(now - 1_000, now), b.samples().map { it.at })
        assertEquals(sample(now), b.samples().last())
    }

    @Test
    fun samplesOlderThanThirtyDaysAreDropped() = runTest {
        val h = history()
        h.load(); advanceUntilIdle()
        h.add(sample(now - 31 * day))
        h.add(sample(now - 29 * day))
        assertEquals(listOf(now - 29 * day), h.samples().map { it.at })
        now += 2 * day
        assertTrue("dropped when read, too", h.samples().isEmpty())
        h.add(sample(now))
        assertEquals(listOf(now), h.samples().map { it.at })
    }

    @Test
    fun deleteRemovesTheFile() = runTest {
        val f = File(tmp.root, "memory_history.json")
        val h = history(f)
        h.load(); advanceUntilIdle()
        h.add(sample(now)); advanceUntilIdle()
        assertTrue(f.isFile)
        h.clear(); advanceUntilIdle()
        assertTrue(h.samples().isEmpty())
        assertFalse(f.exists())
        assertEquals(null, h.lastSampleAt)
    }

    @Test
    fun aDeleteBeforeTheFileWasReadStillDeletesIt() = runTest {
        val f = File(tmp.root, "memory_history.json")
        f.writeText(encodeMemorySamples(listOf(sample(now))))
        val h = history(f)
        h.load()
        h.clear()
        advanceUntilIdle()
        assertTrue(h.samples().isEmpty())
        assertFalse(f.exists())
    }

    @Test
    fun aBadFileReadsAsEmpty() {
        assertTrue(decodeMemorySamples("not json").isEmpty())
        assertTrue(decodeMemorySamples("""{"samples":[{"t":"x"},{"total":5}]}""").isEmpty())
    }
}

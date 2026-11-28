package com.shibi.orbis.tabs

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4 of tabs24-1: how long the stored tab list takes to save and to load with 50 and 500 tabs. No limit is asserted on the
 * time (the brief sets none); the numbers go to the test output as TIMING lines, and the test checks the file round trip.
 * JVM on the build machine, not a phone: the figures show how the cost grows, not what a device needs.
 */
class TabStoreManyTabsTimingTest {
    private val dir: File = Files.createTempDirectory("tabs-timing").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun snapshot(count: Int, stateChars: Int): TabsSnapshot {
        val state = "s".repeat(stateChars)
        val tabs = (1..count).map {
            PersistedTab(it.toLong(), "https://site$it.example/some/long/path/page-$it?q=$it", "Title of page number $it", false, state, groupId = null)
        }
        return TabsSnapshot(tabs, selectedId = 1)
    }

    private fun measure(count: Int, stateChars: Int) {
        val store = FileTabStore(File(dir, "tabs-$count-$stateChars/tabs.json"))
        val snap = snapshot(count, stateChars)
        repeat(3) { store.save(snap); store.load() } // warm up the JIT
        val saves = LongArray(7)
        val loads = LongArray(7)
        var loaded: TabsSnapshot? = null
        for (i in saves.indices) {
            var t = System.nanoTime()
            store.save(snap)
            saves[i] = System.nanoTime() - t
            t = System.nanoTime()
            loaded = store.load()
            loads[i] = System.nanoTime() - t
        }
        val bytes = File(dir, "tabs-$count-$stateChars/tabs.json").length()
        assertEquals(count, loaded!!.tabs.size)
        val withState = loaded.tabs.count { it.savedState != null }
        println(
            "TIMING tabs=$count stateChars=$stateChars fileKB=${bytes / 1024} tabsWithSavedState=$withState " +
                "saveMedianMs=${"%.1f".format(saves.sorted()[3] / 1e6)} loadMedianMs=${"%.1f".format(loads.sorted()[3] / 1e6)} " +
                "saveMaxMs=${"%.1f".format(saves.max() / 1e6)} loadMaxMs=${"%.1f".format(loads.max() / 1e6)}",
        )
    }

    @Test
    fun saveAndLoadTimesForFiftyAndFiveHundredTabs() {
        measure(50, 0)
        measure(500, 0)
        measure(50, 8_000)
        measure(500, 8_000)
        // Every tab with 20 KB of page state: the 8 MB budget of the codec cuts the state of the later tabs, not the tabs.
        measure(500, 20_000)
        assertTrue(File(dir, "tabs-500-0/tabs.json").isFile)
    }
}

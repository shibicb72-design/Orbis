package com.shibi.orbis.backup

import com.shibi.orbis.tabs.TabSleepAfter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings > Put unused tabs to sleep goes into a backup and comes back from it with the other settings. */
@OptIn(ExperimentalCoroutinesApi::class)
class TabSleepBackupTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() = scope.cancel()

    private fun pw() = "correct horse battery".toCharArray()

    private fun world(): BackupWorld = BackupWorld(scope, dispatcher, scheduler).apply { load() }

    @Test(timeout = 60_000)
    fun eachChoiceSurvivesABackupRoundTrip() =
        runTest(dispatcher) {
            for (choice in listOf(TabSleepAfter.NEVER, TabSleepAfter.AFTER_1_DAY, TabSleepAfter.AFTER_15_MINUTES)) {
                val from = world()
                from.browserSettings.setTabSleepAfter(choice)
                val file = from.manager.create(pw())
                val to = world()
                assertEquals(TabSleepAfter.AFTER_1_HOUR, to.browserSettings.tabSleepAfter.value)
                val result = to.manager.restore(file, pw()) as RestoreResult.Restored
                scheduler.advanceUntilIdle()
                assertEquals(emptySet<String>(), result.failedSections)
                assertEquals("$choice", choice, to.browserSettings.tabSleepAfter.value)
                assertEquals("$choice, stored", choice, to.browserPrefs.tabSleepAfter)
            }
        }

    @Test
    fun theSettingIsOnTheAllowListAndInTheWriterUnderTheSameKey() {
        val allowed = SettingsAllowList.files.getValue(com.shibi.orbis.settings.SharedPreferencesBrowserPrefs.FILE_NAME)
        val type = allowed.getValue("tab_sleep_after")
        for (choice in TabSleepAfter.entries) assertEquals(choice.name, type.parse(choice.name))
        assertEquals("an unknown choice is not written", null, type.parse("AFTER_1_WEEK"))
        val w = world()
        w.browserSettings.setTabSleepAfter(TabSleepAfter.AFTER_1_DAY)
        assertEquals("AFTER_1_DAY", w.manager.settings().getValue("browser_prefs").getValue("tab_sleep_after").read())
    }
}

package com.shibi.orbis.tabs

import org.junit.Assert.assertEquals
import org.junit.Test

class TabSleepVerdictTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 10 * 24 * hour

    /** A background normal tab with a page, last looked at two days ago: the one case that sleeps under every setting. */
    private val idle =
        TabSleepFacts(
            isPrivate = false,
            isSelected = false,
            hasSession = true,
            hasSavedState = true,
            lastLookedAt = now - 2 * 24 * hour,
            awakeSince = now - 3 * 24 * hour,
        )

    private fun verdict(
        facts: TabSleepFacts,
        setting: TabSleepAfter = TabSleepAfter.AFTER_1_HOUR,
        lowMemory: Boolean = false,
    ) = tabSleepVerdict(facts, now, setting, lowMemory)

    @Test
    fun anIdleBackgroundTabSleepsUnderEverySettingButNever() {
        assertEquals(TabSleepVerdict.SLEEP, verdict(idle, TabSleepAfter.AFTER_15_MINUTES))
        assertEquals(TabSleepVerdict.SLEEP, verdict(idle, TabSleepAfter.AFTER_1_HOUR))
        assertEquals(TabSleepVerdict.SLEEP, verdict(idle, TabSleepAfter.AFTER_1_DAY))
        assertEquals(TabSleepVerdict.SETTING_NEVER, verdict(idle, TabSleepAfter.NEVER))
    }

    @Test
    fun eachNeverConditionAloneKeepsTheTabAwake() {
        val cases =
            listOf(
                idle.copy(isSelected = true) to TabSleepVerdict.SELECTED,
                idle.copy(isPrivate = true) to TabSleepVerdict.PRIVATE,
                idle.copy(playing = true) to TabSleepVerdict.PLAYING,
                idle.copy(loading = true) to TabSleepVerdict.LOADING,
                idle.copy(fullScreen = true) to TabSleepVerdict.FULL_SCREEN,
                idle.copy(pictureInPicture = true) to TabSleepVerdict.PICTURE_IN_PICTURE,
                idle.copy(cameraOrMicrophoneInUse = true) to TabSleepVerdict.CAMERA_OR_MICROPHONE,
                idle.copy(casting = true) to TabSleepVerdict.CASTING,
                idle.copy(pageDialogOpen = true) to TabSleepVerdict.PAGE_DIALOG,
                idle.copy(orbisQuestionOpen = true) to TabSleepVerdict.ORBIS_QUESTION,
                idle.copy(uploadRunning = true) to TabSleepVerdict.UPLOAD,
                idle.copy(downloadRunning = true) to TabSleepVerdict.DOWNLOAD,
                idle.copy(hasSavedState = false) to TabSleepVerdict.NO_SAVED_STATE,
                idle.copy(lastLookedAt = null, awakeSince = null) to TabSleepVerdict.TIME_NOT_KNOWN,
            )
        for ((facts, expected) in cases) {
            for (setting in TabSleepAfter.entries.filter { it != TabSleepAfter.NEVER }) {
                assertEquals("$expected under $setting", expected, verdict(facts, setting))
                assertEquals("$expected under $setting, low memory", expected, verdict(facts, setting, lowMemory = true))
            }
        }
    }

    @Test
    fun aTabThatIsAlreadyAsleepIsNotSleptAgain() {
        assertEquals(TabSleepVerdict.ALREADY_ASLEEP, verdict(idle.copy(hasSession = false)))
    }

    @Test
    fun theTimeRuleAtOneMinuteUnderAndOneMinuteOver() {
        for (setting in TabSleepAfter.entries.filter { it != TabSleepAfter.NEVER }) {
            val after = setting.afterMillis!!
            val under = idle.copy(lastLookedAt = now - after + minute, awakeSince = now - 30 * 24 * hour)
            val over = idle.copy(lastLookedAt = now - after - minute, awakeSince = now - 30 * 24 * hour)
            val exactly = idle.copy(lastLookedAt = now - after, awakeSince = now - 30 * 24 * hour)
            assertEquals("$setting, a minute under", TabSleepVerdict.LOOKED_AT_TOO_RECENTLY, verdict(under, setting))
            assertEquals("$setting, exactly", TabSleepVerdict.LOOKED_AT_TOO_RECENTLY, verdict(exactly, setting))
            assertEquals("$setting, a minute over", TabSleepVerdict.SLEEP, verdict(over, setting))
        }
    }

    @Test
    fun theTimeCountsFromTheLaterOfLookedAtAndSessionOpened() {
        // Looked at long ago, but its session was opened (woken) ten minutes ago: not yet.
        val wokenRecently = idle.copy(lastLookedAt = now - 2 * 24 * hour, awakeSince = now - 10 * minute)
        assertEquals(TabSleepVerdict.LOOKED_AT_TOO_RECENTLY, verdict(wokenRecently, TabSleepAfter.AFTER_15_MINUTES))
        // Opened in the background and never looked at: the time counts from when it opened.
        val neverLooked = idle.copy(lastLookedAt = null, awakeSince = now - 16 * minute)
        assertEquals(TabSleepVerdict.SLEEP, verdict(neverLooked, TabSleepAfter.AFTER_15_MINUTES))
        assertEquals(TabSleepVerdict.LOOKED_AT_TOO_RECENTLY, verdict(neverLooked, TabSleepAfter.AFTER_1_HOUR))
        // A stored look time with no session time (the session time is not known): the look time decides.
        val lookOnly = idle.copy(lastLookedAt = now - 16 * minute, awakeSince = null)
        assertEquals(TabSleepVerdict.SLEEP, verdict(lookOnly, TabSleepAfter.AFTER_15_MINUTES))
    }

    @Test
    fun lowMemoryUsesFiveMinutesInsteadOfTheSetTime() {
        val six = idle.copy(lastLookedAt = now - 6 * minute, awakeSince = now - 6 * minute)
        val four = idle.copy(lastLookedAt = now - 4 * minute, awakeSince = now - 4 * minute)
        for (setting in TabSleepAfter.entries.filter { it != TabSleepAfter.NEVER }) {
            assertEquals("$setting", TabSleepVerdict.LOOKED_AT_TOO_RECENTLY, verdict(six, setting))
            assertEquals("$setting, low memory", TabSleepVerdict.SLEEP, verdict(six, setting, lowMemory = true))
            assertEquals("$setting, low memory, 4 min", TabSleepVerdict.LOOKED_AT_TOO_RECENTLY, verdict(four, setting, lowMemory = true))
        }
    }

    @Test
    fun neverSleepsNothingEvenWhenMemoryIsLow() {
        val candidates =
            listOf(
                idle,
                idle.copy(lastLookedAt = now - 365 * 24 * hour, awakeSince = null),
                idle.copy(lastLookedAt = null, awakeSince = now - 365 * 24 * hour),
            )
        for (facts in candidates) {
            assertEquals(TabSleepVerdict.SETTING_NEVER, verdict(facts, TabSleepAfter.NEVER))
            assertEquals(TabSleepVerdict.SETTING_NEVER, verdict(facts, TabSleepAfter.NEVER, lowMemory = true))
        }
    }

    @Test
    fun aPlayingTabStaysAwakeAndBecomesEligibleOnlyTheSetTimeAfterItWasLastLookedAt() {
        // Left (looked at for the last time) 50 minutes ago and still playing: awake.
        val left = now - 50 * minute
        val playing = idle.copy(lastLookedAt = left, awakeSince = left - hour, playing = true)
        assertEquals(TabSleepVerdict.PLAYING, verdict(playing, TabSleepAfter.AFTER_15_MINUTES))
        // It stops: the 15 minutes have long passed since it was last looked at, so it may sleep at once...
        assertEquals(TabSleepVerdict.SLEEP, verdict(playing.copy(playing = false), TabSleepAfter.AFTER_15_MINUTES))
        // ...but with "After 1 hour" it waits until an hour after it was last LOOKED at, not after it stopped.
        assertEquals(TabSleepVerdict.LOOKED_AT_TOO_RECENTLY, verdict(playing.copy(playing = false), TabSleepAfter.AFTER_1_HOUR))
        val anHourAndAMinuteAfterLeaving = left + hour + minute
        assertEquals(
            TabSleepVerdict.SLEEP,
            tabSleepVerdict(playing.copy(playing = false), anHourAndAMinuteAfterLeaving, TabSleepAfter.AFTER_1_HOUR),
        )
    }

    @Test
    fun eachChoiceHasItsTime() {
        assertEquals(60 * minute, TabSleepAfter.AFTER_1_HOUR.afterMillis)
        assertEquals(15 * minute, TabSleepAfter.AFTER_15_MINUTES.afterMillis)
        assertEquals(24 * hour, TabSleepAfter.AFTER_1_DAY.afterMillis)
        assertEquals(null, TabSleepAfter.NEVER.afterMillis)
    }
}

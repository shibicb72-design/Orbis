package com.shibi.orbis.tabs

/** Settings > Put unused tabs to sleep. [afterMillis] is null for [NEVER]. Default [AFTER_1_HOUR] (Shibi, 2026-11-24). */
enum class TabSleepAfter(val afterMillis: Long?) {
    AFTER_15_MINUTES(15 * 60_000L),
    AFTER_1_HOUR(60 * 60_000L),
    AFTER_1_DAY(24 * 60 * 60_000L),
    NEVER(null),
}

/** When Android reports that memory is running low, a tab not looked at for this long may sleep (unless the setting is Never). */
const val LOW_MEMORY_SLEEP_AFTER_MILLIS = 5 * 60_000L

/**
 * What Orbis knows about one tab at the moment of the check, without touching its page. Every field is read from state
 * the tab layer already holds; none is measured.
 */
data class TabSleepFacts(
    val isPrivate: Boolean,
    val isSelected: Boolean,
    /** False for a tab that is already asleep (restored and never shown, or slept before). */
    val hasSession: Boolean,
    /** The engine has given a saved state for the page (history, scroll position, form data). */
    val hasSavedState: Boolean,
    /** When the user last looked at the tab, or null when that is not known. */
    val lastLookedAt: Long?,
    /** When the tab's current session was opened, or null when that is not known. */
    val awakeSince: Long?,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val fullScreen: Boolean = false,
    val pictureInPicture: Boolean = false,
    val cameraOrMicrophoneInUse: Boolean = false,
    val casting: Boolean = false,
    /** A page dialog (alert, file chooser, a site permission or password-save question...) waits in the session. */
    val pageDialogOpen: Boolean = false,
    /** An Orbis question for this tab is open: a download or torrent offer, the long-press menu. */
    val orbisQuestionOpen: Boolean = false,
    val uploadRunning: Boolean = false,
    val downloadRunning: Boolean = false,
)

/** Why the check came out as it did. [SLEEP] is the only answer that lets the tab be put to sleep. */
enum class TabSleepVerdict {
    SLEEP,
    SETTING_NEVER,
    ALREADY_ASLEEP,
    SELECTED,
    PRIVATE,
    PLAYING,
    LOADING,
    FULL_SCREEN,
    PICTURE_IN_PICTURE,
    CAMERA_OR_MICROPHONE,
    CASTING,
    PAGE_DIALOG,
    ORBIS_QUESTION,
    UPLOAD,
    DOWNLOAD,
    NO_SAVED_STATE,
    TIME_NOT_KNOWN,
    LOOKED_AT_TOO_RECENTLY,
}

/**
 * May this tab be put to sleep now? Pure. [setting] is the user's choice; [lowMemory] true when Android reported that
 * memory is running low, which shortens the time to [LOW_MEMORY_SLEEP_AFTER_MILLIS] but never overrides Never.
 *
 * The time counts from the later of [TabSleepFacts.lastLookedAt] and [TabSleepFacts.awakeSince], so a tab that was
 * opened in the background and never looked at gets the full time too. The tab must have been idle for strictly longer
 * than the time.
 */
fun tabSleepVerdict(facts: TabSleepFacts, now: Long, setting: TabSleepAfter, lowMemory: Boolean = false): TabSleepVerdict {
    val setMillis = setting.afterMillis ?: return TabSleepVerdict.SETTING_NEVER
    val afterMillis = if (lowMemory) minOf(setMillis, LOW_MEMORY_SLEEP_AFTER_MILLIS) else setMillis
    return when {
        !facts.hasSession -> TabSleepVerdict.ALREADY_ASLEEP
        facts.isSelected -> TabSleepVerdict.SELECTED
        facts.isPrivate -> TabSleepVerdict.PRIVATE
        facts.playing -> TabSleepVerdict.PLAYING
        facts.loading -> TabSleepVerdict.LOADING
        facts.fullScreen -> TabSleepVerdict.FULL_SCREEN
        facts.pictureInPicture -> TabSleepVerdict.PICTURE_IN_PICTURE
        facts.cameraOrMicrophoneInUse -> TabSleepVerdict.CAMERA_OR_MICROPHONE
        facts.casting -> TabSleepVerdict.CASTING
        facts.pageDialogOpen -> TabSleepVerdict.PAGE_DIALOG
        facts.orbisQuestionOpen -> TabSleepVerdict.ORBIS_QUESTION
        facts.uploadRunning -> TabSleepVerdict.UPLOAD
        facts.downloadRunning -> TabSleepVerdict.DOWNLOAD
        // Without a saved state the page could only come back from its address, without its history.
        !facts.hasSavedState -> TabSleepVerdict.NO_SAVED_STATE
        else -> {
            val since = listOfNotNull(facts.lastLookedAt, facts.awakeSince).maxOrNull()
            when {
                since == null -> TabSleepVerdict.TIME_NOT_KNOWN
                now - since > afterMillis -> TabSleepVerdict.SLEEP
                else -> TabSleepVerdict.LOOKED_AT_TOO_RECENTLY
            }
        }
    }
}

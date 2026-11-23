package com.shibi.orbis.engine

/**
 * Whether private tabs play protected video (DRM). Shibi's decision of 2026-11-23: they do not, whatever the setting
 * says. The one place this choice is made.
 */
const val PROTECTED_VIDEO_IN_PRIVATE_TABS = false

/** Orbis's answer to a page's request for protected video (DRM). */
enum class ProtectedVideoAnswer {
    ALLOW,

    /** Refused: Settings > Play protected video (DRM) is off. */
    DENY_SETTING_OFF,

    /** Refused: a private tab (see [PROTECTED_VIDEO_IN_PRIVATE_TABS]). */
    DENY_PRIVATE_TAB,
}

/**
 * The answer to a DRM request. [settingOn] is Settings > Play protected video (DRM), [isPrivate] the tab's mode.
 * Nothing is remembered per site: each request is answered from these alone. A private tab is refused for being
 * private even when the setting is off, so its notice names the reason that turning the setting on would not change.
 */
fun protectedVideoAnswer(
    settingOn: Boolean,
    isPrivate: Boolean,
    allowInPrivateTabs: Boolean = PROTECTED_VIDEO_IN_PRIVATE_TABS,
): ProtectedVideoAnswer =
    when {
        isPrivate && !allowInPrivateTabs -> ProtectedVideoAnswer.DENY_PRIVATE_TAB
        !settingOn -> ProtectedVideoAnswer.DENY_SETTING_OFF
        else -> ProtectedVideoAnswer.ALLOW
    }

/**
 * A refused DRM request of the page, for the notice (memory only). Page loads are counted by [SessionState.loadStarts].
 * [refusedLoad] is the load in which a request was last refused and [answer] why; [noticeShownLoad] the load whose
 * notice has appeared, so it shows once per page load.
 */
data class ProtectedVideoRefusal(
    val refusedLoad: Int? = null,
    val answer: ProtectedVideoAnswer? = null,
    val noticeShownLoad: Int? = null,
)

/** A DRM request of the current page was answered [answer]; only a refusal is recorded. */
internal fun SessionState.afterProtectedVideoAnswer(answer: ProtectedVideoAnswer): SessionState =
    if (answer == ProtectedVideoAnswer.ALLOW) {
        this
    } else {
        copy(protectedVideo = protectedVideo.copy(refusedLoad = loadStarts, answer = answer))
    }

/** The notice for page load [load] has appeared. */
internal fun SessionState.afterProtectedVideoNoticeShown(load: Int): SessionState =
    copy(protectedVideo = protectedVideo.copy(noticeShownLoad = load))

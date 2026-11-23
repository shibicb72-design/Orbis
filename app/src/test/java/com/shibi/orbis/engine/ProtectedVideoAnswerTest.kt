package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The answer to a page's protected video (DRM) request, and what the page state keeps of it for the notice. */
class ProtectedVideoAnswerTest {
    @Test
    fun settingOnInANormalTabAllows() {
        assertEquals(ProtectedVideoAnswer.ALLOW, protectedVideoAnswer(settingOn = true, isPrivate = false))
    }

    @Test
    fun settingOffInANormalTabRefusesForTheSetting() {
        assertEquals(ProtectedVideoAnswer.DENY_SETTING_OFF, protectedVideoAnswer(settingOn = false, isPrivate = false))
    }

    @Test
    fun aPrivateTabRefusesWithTheSettingOn() {
        assertEquals(ProtectedVideoAnswer.DENY_PRIVATE_TAB, protectedVideoAnswer(settingOn = true, isPrivate = true))
    }

    @Test
    fun aPrivateTabRefusesWithThePrivateReasonWhenTheSettingIsOffToo() {
        assertEquals(ProtectedVideoAnswer.DENY_PRIVATE_TAB, protectedVideoAnswer(settingOn = false, isPrivate = true))
    }

    @Test
    fun privateTabsDoNotPlayProtectedVideo() {
        assertFalse(PROTECTED_VIDEO_IN_PRIVATE_TABS)
    }

    @Test
    fun withPrivateTabsAllowedThePrivateTabFollowsTheSetting() {
        assertEquals(ProtectedVideoAnswer.ALLOW, protectedVideoAnswer(settingOn = true, isPrivate = true, allowInPrivateTabs = true))
        assertEquals(
            ProtectedVideoAnswer.DENY_SETTING_OFF,
            protectedVideoAnswer(settingOn = false, isPrivate = true, allowInPrivateTabs = true),
        )
        assertEquals(ProtectedVideoAnswer.ALLOW, protectedVideoAnswer(settingOn = true, isPrivate = false, allowInPrivateTabs = true))
    }

    @Test
    fun aSitePolicyThatDoesNotSayRefuses() {
        val silent =
            object : SitePolicy {
                override fun desktopFor(url: String, isPrivate: Boolean) = false
                override fun javaScriptFor(url: String, isPrivate: Boolean) = true
            }
        assertFalse(silent.protectedVideoOn())
    }

    @Test
    fun anAllowLeavesThePageStateAloneARefusalRecordsItsLoadAndReason() {
        val page = SessionState().afterPageStart().afterPageStart()
        assertEquals(page, page.afterProtectedVideoAnswer(ProtectedVideoAnswer.ALLOW))
        assertEquals(
            ProtectedVideoRefusal(refusedLoad = 2, answer = ProtectedVideoAnswer.DENY_SETTING_OFF),
            page.afterProtectedVideoAnswer(ProtectedVideoAnswer.DENY_SETTING_OFF).protectedVideo,
        )
        assertEquals(
            ProtectedVideoRefusal(refusedLoad = 2, answer = ProtectedVideoAnswer.DENY_PRIVATE_TAB, noticeShownLoad = 2),
            page.afterProtectedVideoAnswer(ProtectedVideoAnswer.DENY_PRIVATE_TAB).afterProtectedVideoNoticeShown(2).protectedVideo,
        )
    }
}

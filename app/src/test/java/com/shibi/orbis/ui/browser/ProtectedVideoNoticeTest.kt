package com.shibi.orbis.ui.browser

import com.shibi.orbis.engine.ProtectedVideoAnswer
import com.shibi.orbis.engine.ProtectedVideoRefusal
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.engine.afterProtectedVideoAnswer
import com.shibi.orbis.engine.afterProtectedVideoNoticeShown
import com.shibi.orbis.engine.protectedVideoAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision "which protected-video notice to show now, if any" and the page-state steps that feed it. */
class ProtectedVideoNoticeTest {
    private fun notice(
        refusal: ProtectedVideoRefusal,
        currentLoad: Int = 3,
        tabSelected: Boolean = true,
        inFront: Boolean = true,
        pageHidden: Boolean = false,
        inPip: Boolean = false,
    ) = protectedVideoNoticeNow(refusal, currentLoad, tabSelected, inFront, pageHidden, inPip)

    private val offNow = ProtectedVideoRefusal(refusedLoad = 3, answer = ProtectedVideoAnswer.DENY_SETTING_OFF)

    /** A page of load 1 whose request was answered as the engine would, from the setting and the tab's mode. */
    private fun asked(settingOn: Boolean, isPrivate: Boolean): SessionState =
        SessionState().afterPageStart().afterProtectedVideoAnswer(protectedVideoAnswer(settingOn, isPrivate))

    private fun noticeFor(page: SessionState) = notice(page.protectedVideo, currentLoad = page.loadStarts)

    @Test
    fun settingOnNoNoticeInANormalTab() {
        assertNull(noticeFor(asked(settingOn = true, isPrivate = false)))
    }

    @Test
    fun settingOffTheSettingsNoticeInANormalTab() {
        assertEquals(ProtectedVideoAnswer.DENY_SETTING_OFF, noticeFor(asked(settingOn = false, isPrivate = false)))
    }

    @Test
    fun privateTabWithTheSettingOnThePrivateTabNotice() {
        assertEquals(ProtectedVideoAnswer.DENY_PRIVATE_TAB, noticeFor(asked(settingOn = true, isPrivate = true)))
    }

    @Test
    fun privateTabWithTheSettingOffThePrivateTabNoticeNotTheSettingsOne() {
        assertEquals(ProtectedVideoAnswer.DENY_PRIVATE_TAB, noticeFor(asked(settingOn = false, isPrivate = true)))
    }

    @Test
    fun anAllowedRequestNeverShowsANoticeEvenIfRecorded() {
        assertNull(notice(ProtectedVideoRefusal(refusedLoad = 3, answer = ProtectedVideoAnswer.ALLOW)))
    }

    @Test
    fun noRefusalShowsNothing() {
        assertNull(notice(ProtectedVideoRefusal()))
    }

    @Test
    fun aTabThatIsNotSelectedShowsNothing() {
        assertNull(notice(offNow, tabSelected = false))
    }

    @Test
    fun aSecondTimeInTheSamePageLoadShowsNothing() {
        assertNull(notice(offNow.copy(noticeShownLoad = 3)))
    }

    @Test
    fun aNewPageLoadAfterAnEarlierNoticeIsShownAgain() {
        assertEquals(ProtectedVideoAnswer.DENY_SETTING_OFF, notice(offNow.copy(refusedLoad = 4, noticeShownLoad = 3), currentLoad = 4))
    }

    @Test
    fun aRefusalOfAnEarlierPageLoadIsNotShownOnTheNextPage() {
        assertNull(notice(offNow.copy(refusedLoad = 2), currentLoad = 3))
    }

    @Test
    fun notInFrontShowsNothing() {
        assertNull(notice(offNow, inFront = false))
    }

    @Test
    fun aPageHiddenByThePrivateLockShowsNothing() {
        assertNull(notice(offNow, pageHidden = true))
    }

    @Test
    fun pictureInPictureShowsNothing() {
        assertNull(notice(offNow, inPip = true))
    }

    @Test
    fun mayStayOnlyWhileEveryConditionHolds() {
        assertTrue(protectedVideoNoticeMayStay(tabSelected = true, inFront = true, pageHidden = false, inPip = false))
        assertFalse(protectedVideoNoticeMayStay(tabSelected = false, inFront = true, pageHidden = false, inPip = false))
        assertFalse(protectedVideoNoticeMayStay(tabSelected = true, inFront = false, pageHidden = false, inPip = false))
        assertFalse(protectedVideoNoticeMayStay(tabSelected = true, inFront = true, pageHidden = true, inPip = false))
        assertFalse(protectedVideoNoticeMayStay(tabSelected = true, inFront = true, pageHidden = false, inPip = true))
    }

    @Test
    fun tellsOncePerPageLoadAndAgainAfterAReloadThatIsRefused() {
        val refused = asked(settingOn = false, isPrivate = false)
        assertEquals(ProtectedVideoAnswer.DENY_SETTING_OFF, noticeFor(refused))
        val told = refused.afterProtectedVideoNoticeShown(refused.loadStarts)
        assertNull(noticeFor(told))
        // The page asks again in the same load: still told.
        assertNull(noticeFor(told.afterProtectedVideoAnswer(ProtectedVideoAnswer.DENY_SETTING_OFF)))
        // A reload is a new page load: told again once it is refused again, not before.
        val reloaded = told.afterPageStart()
        assertNull(noticeFor(reloaded))
        assertEquals(ProtectedVideoAnswer.DENY_SETTING_OFF, noticeFor(reloaded.afterProtectedVideoAnswer(ProtectedVideoAnswer.DENY_SETTING_OFF)))
        // The setting was turned on and the page reloaded: allowed, no notice.
        assertNull(noticeFor(reloaded.afterProtectedVideoAnswer(ProtectedVideoAnswer.ALLOW)))
    }
}

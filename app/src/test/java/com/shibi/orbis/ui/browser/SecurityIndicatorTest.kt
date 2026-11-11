package com.shibi.orbis.ui.browser

import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.afterContentProcessGone
import com.shibi.orbis.engine.afterLoadError
import com.shibi.orbis.engine.afterLocationChange
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.engine.afterPageStop
import com.shibi.orbis.engine.afterSecurityChange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-14: the lock follows Gecko's security report, in the order the engine applies its events, never the URL scheme. */
class SecurityIndicatorTest {
    private fun SessionState.report(secure: Boolean = true, exception: Boolean = false, mixedActive: Boolean = false) =
        afterSecurityChange(connectionSecure = secure, certificateException = exception, mixedActiveLoaded = mixedActive)

    private fun load(from: SessionState, url: String) = from.afterPageStart().afterLocationChange(url)

    private val securePage = load(SessionState(), "https://a.example/").report().afterPageStop(success = true)

    @Test
    fun httpsReportedSecureShowsTheLock() {
        assertTrue(showsLock(securePage))
    }

    @Test
    fun httpsBeforeTheReportShowsNoLock() {
        assertFalse(showsLock(SessionState(url = "https://a.example/")))
        assertFalse(showsLock(load(SessionState(), "https://a.example/")))
    }

    @Test
    fun mixedActiveContentShowsNoLock() {
        assertFalse(showsLock(load(SessionState(), "https://a.example/").report(mixedActive = true)))
        // Mixed script loaded after the page was reported secure takes the lock away.
        assertFalse(showsLock(securePage.report(mixedActive = true)))
    }

    @Test
    fun certificateExceptionOrInsecureReportShowsNoLock() {
        assertFalse(showsLock(load(SessionState(), "https://a.example/").report(exception = true)))
        assertFalse(showsLock(load(SessionState(), "https://a.example/").report(secure = false)))
    }

    @Test
    fun httpShowsNoLock() {
        assertFalse(showsLock(load(SessionState(), "http://a.example/").report(secure = false)))
        // Even a stray secure report never puts a lock on plain http.
        assertFalse(showsLock(load(SessionState(), "http://a.example/").report()))
    }

    @Test
    fun importAndBlankShowNoLock() {
        val imported = "file:///data/user/0/com.shibi.orbis/cache/imports/1/report.pdf"
        assertFalse(showsLock(load(SessionState(), imported).report()))
        assertFalse(showsLock(securePage.afterLocationChange(imported)))
        assertFalse(showsLock(SessionState()))
        assertFalse(showsLock(load(SessionState(), "about:blank").report()))
    }

    @Test
    fun aNewPageStartsWithoutTheLock() {
        assertFalse(showsLock(securePage.afterPageStart()))
    }

    @Test
    fun anotherOriginDropsTheLockUntilItsReport() {
        val moved = securePage.afterLocationChange("https://b.example/")
        assertFalse(showsLock(moved))
        assertTrue(showsLock(moved.report()))
        assertFalse(showsLock(securePage.afterLocationChange("https://a.example:8443/")))
    }

    // Gecko sends no new report when a load ends without a location change (failed, cancelled, a download link).

    @Test
    fun aLoadThatFailsGivesTheOldPageItsLockBack() {
        assertTrue(showsLock(securePage.afterPageStart().afterLoadError("https://broken.example/")))
        assertTrue(showsLock(securePage.afterPageStart().afterLoadError(null)))
        assertTrue(showsLock(securePage.afterPageStart().afterPageStop(success = false)))
    }

    @Test
    fun aLoadThatCommitsAnotherOriginDoesNotGetTheOldLock() {
        assertFalse(showsLock(securePage.afterPageStart().afterLocationChange("https://b.example/").afterPageStop(success = true)))
    }

    @Test
    fun anInsecureReportDuringTheLoadWins() {
        assertFalse(showsLock(securePage.afterPageStart().report(secure = false).afterPageStop(success = true)))
    }

    @Test
    fun anInsecurePageStaysWithoutALockAfterAFailedLoad() {
        val insecure = load(SessionState(), "https://a.example/").report(secure = false).afterPageStop(success = true)
        assertFalse(showsLock(insecure.afterPageStart().afterLoadError("https://broken.example/")))
    }

    @Test
    fun twoStartsThenAFailedStopGiveTheLockBack() {
        assertTrue(showsLock(securePage.afterPageStart().afterPageStart().afterPageStop(success = false)))
    }

    @Test
    fun aCrashedPageShowsNoLock() {
        assertFalse(showsLock(securePage.afterContentProcessGone()))
        assertFalse(showsLock(securePage.afterPageStart().afterContentProcessGone().afterPageStop(success = false)))
    }

    @Test
    fun sameOriginLocationChangeKeepsTheLock() {
        assertTrue(showsLock(securePage.afterLocationChange("https://a.example/next#part")))
        assertTrue(showsLock(securePage.afterLocationChange("HTTPS://A.EXAMPLE/other")))
    }
}

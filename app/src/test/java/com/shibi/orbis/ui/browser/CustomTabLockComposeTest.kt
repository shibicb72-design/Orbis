package com.shibi.orbis.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.shibi.orbis.customtabs.CustomTabAddressLine
import com.shibi.orbis.engine.SessionState
import com.shibi.orbis.engine.afterLocationChange
import com.shibi.orbis.engine.afterPageStart
import com.shibi.orbis.engine.afterSecurityChange
import com.shibi.orbis.settings.AppTheme
import com.shibi.orbis.ui.theme.OrbisTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F-14: the Custom Tab shows its lock only after Gecko reported the page secure. Light and dark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CustomTabLockComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val loaded = SessionState().afterPageStart().afterLocationChange("https://www.a.example/page")

    private fun show(theme: AppTheme, state: SessionState) {
        rule.setContent {
            OrbisTheme(theme) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
                    CustomTabAddressLine(state)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun check(theme: AppTheme, state: SessionState, lock: Boolean) {
        show(theme, state)
        rule.onNodeWithText("a.example").assertIsDisplayed()
        val node = rule.onNodeWithContentDescription("Secure connection")
        if (lock) node.assertIsDisplayed() else node.assertDoesNotExist()
    }

    private fun secure(mixedActive: Boolean = false) =
        loaded.afterSecurityChange(connectionSecure = true, certificateException = false, mixedActiveLoaded = mixedActive)

    @Test fun reportedSecureShowsLockLight() = check(AppTheme.LIGHT, secure(), lock = true)

    @Test fun reportedSecureShowsLockDark() = check(AppTheme.DARK, secure(), lock = true)

    @Test fun httpsBeforeReportShowsNoLockLight() = check(AppTheme.LIGHT, loaded, lock = false)

    @Test fun httpsBeforeReportShowsNoLockDark() = check(AppTheme.DARK, loaded, lock = false)

    @Test fun mixedActiveShowsNoLockLight() = check(AppTheme.LIGHT, secure(mixedActive = true), lock = false)

    @Test fun mixedActiveShowsNoLockDark() = check(AppTheme.DARK, secure(mixedActive = true), lock = false)
}

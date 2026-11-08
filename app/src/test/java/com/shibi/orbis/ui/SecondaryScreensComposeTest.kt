package com.shibi.orbis.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 3: secondary content is capped and centered on wide windows, unchanged on narrow ones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1200dp-h800dp")
class SecondaryScreensComposeTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun measure(windowWidthDp: Int) {
        rule.setContent {
            Box(Modifier.width(windowWidthDp.dp).testTag("window")) {
                Column(Modifier.secondaryReadingWidth().testTag("content")) {
                    Box(Modifier.fillMaxWidth().height(10.dp).testTag("row"))
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aWideWindowCapsAndCentersTheContent() {
        measure(1100)
        val content = rule.onNodeWithTag("content").getBoundsInRoot()
        val row = rule.onNodeWithTag("row").getBoundsInRoot()
        assertEquals(SecondaryReadingWidth, content.right - content.left)
        assertEquals("rows fill the capped column", SecondaryReadingWidth, row.right - row.left)
        assertEquals("centered", (1100.dp - SecondaryReadingWidth) / 2, content.left)
    }

    @Test
    fun aNarrowWindowIsUnchanged() {
        measure(360)
        val content = rule.onNodeWithTag("content").getBoundsInRoot()
        assertEquals(360.dp, content.right - content.left)
        assertEquals(0.dp, content.left)
    }
}

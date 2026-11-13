package com.shibi.orbis.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [BrowserFrame]'s layout: the strip on top, the chrome above or below the page, and the page filling the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BrowserFrameTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var showChrome by mutableStateOf(true)
    private var showStrip by mutableStateOf(false)
    private var toolbarBottom by mutableStateOf(false)

    private fun show() {
        rule.setContent {
            Box(Modifier.size(400.dp, 800.dp).testTag(FRAME)) {
                BrowserFrame(
                    showChrome = showChrome,
                    showStrip = showStrip,
                    toolbarBottom = toolbarBottom,
                    modifier = Modifier,
                    tabStrip = { Box(Modifier.fillMaxWidth().height(40.dp).testTag(STRIP)) },
                    // Like the browser's chrome: several rows, which must stack.
                    chrome = {
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag(TOOLBAR))
                        Box(Modifier.fillMaxWidth().height(4.dp).testTag(PROGRESS))
                    },
                    page = { Box(Modifier.fillMaxSize().testTag(PAGE)) },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun px(dp: Int): Float = with(rule.density) { dp.dp.toPx() }

    @Test
    fun aTopToolbarSitsAboveThePage() {
        show()
        val frame = bounds(FRAME)
        assertEquals(frame.top, bounds(TOOLBAR).top, 0.5f)
        assertEquals(bounds(TOOLBAR).bottom, bounds(PROGRESS).top, 0.5f)
        assertEquals(bounds(PROGRESS).bottom, bounds(PAGE).top, 0.5f)
        assertEquals(frame.bottom, bounds(PAGE).bottom, 0.5f)
    }

    @Test
    fun aBottomToolbarSitsBelowThePage() {
        toolbarBottom = true
        show()
        val frame = bounds(FRAME)
        assertEquals(frame.top, bounds(PAGE).top, 0.5f)
        assertEquals(bounds(PAGE).bottom, bounds(TOOLBAR).top, 0.5f)
        assertEquals(bounds(TOOLBAR).bottom, bounds(PROGRESS).top, 0.5f)
        assertEquals(frame.bottom, bounds(PROGRESS).bottom, 0.5f)
    }

    @Test
    fun withoutChromeThePageFillsTheFrame() {
        showChrome = false
        show()
        assertEquals(bounds(FRAME), bounds(PAGE))
        rule.onNodeWithTag(TOOLBAR).assertDoesNotExist()
        rule.onNodeWithTag(PROGRESS).assertDoesNotExist()
        rule.onNodeWithTag(STRIP).assertDoesNotExist()
        toolbarBottom = true
        rule.waitForIdle()
        assertEquals(bounds(FRAME), bounds(PAGE))
        rule.onNodeWithTag(TOOLBAR).assertDoesNotExist()
    }

    @Test
    fun theStripSitsAboveTheChromeAndThePage() {
        showStrip = true
        show()
        val frame = bounds(FRAME)
        assertEquals(frame.top, bounds(STRIP).top, 0.5f)
        assertEquals(bounds(STRIP).bottom, bounds(TOOLBAR).top, 0.5f)
        assertEquals(bounds(PROGRESS).bottom, bounds(PAGE).top, 0.5f)
        toolbarBottom = true
        rule.waitForIdle()
        assertEquals(frame.top, bounds(STRIP).top, 0.5f)
        assertEquals(bounds(STRIP).bottom, bounds(PAGE).top, 0.5f)
        assertEquals(bounds(PAGE).bottom, bounds(TOOLBAR).top, 0.5f)
        assertEquals(frame.bottom, bounds(PROGRESS).bottom, 0.5f)
    }

    @Test
    fun movingTheToolbarOnlySwapsThePageAndTheChrome() {
        show()
        val top = bounds(PAGE)
        toolbarBottom = true
        rule.waitForIdle()
        val bottom = bounds(PAGE)
        val chrome = px(56 + 4)
        assertEquals(top.width, bottom.width, 0.5f)
        assertEquals(top.left, bottom.left, 0.5f)
        assertEquals(top.height, bottom.height, 0.5f)
        assertEquals(bounds(FRAME).height - chrome, top.height, 0.5f)
        assertEquals(top.top - chrome, bottom.top, 0.5f)
    }

    private companion object {
        const val FRAME = "frame"
        const val STRIP = "strip"
        const val TOOLBAR = "toolbar"
        const val PROGRESS = "progress"
        const val PAGE = "page"
    }
}

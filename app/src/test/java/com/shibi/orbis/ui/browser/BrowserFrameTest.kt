package com.shibi.orbis.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import android.view.View
import com.shibi.orbis.ui.dispatchLeftCutout
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

    private var hostView: View? = null

    /** [insets] null means the default ones (the system bars, and the cutout while the chrome shows). */
    private fun show(insets: WindowInsets? = null) {
        rule.setContent {
            hostView = LocalView.current
            val tabStrip: @Composable () -> Unit = { Box(Modifier.fillMaxWidth().height(40.dp).testTag(STRIP)) }
            // Like the browser's chrome: several rows, which must stack.
            val chrome: @Composable () -> Unit = {
                Box(Modifier.fillMaxWidth().height(56.dp).testTag(TOOLBAR))
                Box(Modifier.fillMaxWidth().height(4.dp).testTag(PROGRESS))
            }
            val page: @Composable BoxScope.() -> Unit = { Box(Modifier.fillMaxSize().testTag(PAGE)) }
            Box(Modifier.size(400.dp, 800.dp).testTag(FRAME)) {
                if (insets == null) {
                    BrowserFrame(showChrome, showStrip, toolbarBottom, Modifier, tabStrip = tabStrip, chrome = chrome, page = page)
                } else {
                    BrowserFrame(showChrome, showStrip, toolbarBottom, Modifier, insets, tabStrip, chrome, page)
                }
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

    @Test
    fun aSideInsetMovesTheStripTheToolbarAndThePageIn() {
        showStrip = true
        show(WindowInsets(left = 40.dp))
        val frame = bounds(FRAME)
        for (tag in listOf(STRIP, TOOLBAR, PROGRESS, PAGE)) {
            assertEquals(tag, frame.left + px(40), bounds(tag).left, 0.5f)
            assertEquals(tag, frame.right, bounds(tag).right, 0.5f)
        }
    }

    @Test
    fun withoutInsetsEverythingStartsAtTheLeftEdge() {
        showStrip = true
        show(WindowInsets(0))
        val frame = bounds(FRAME)
        for (tag in listOf(STRIP, TOOLBAR, PROGRESS, PAGE)) {
            assertEquals(tag, frame.left, bounds(tag).left, 0.5f)
            assertEquals(tag, frame.right, bounds(tag).right, 0.5f)
        }
    }

    @Test
    fun aBottomToolbarKeepsClearOfTheBottomAndLeftInsets() {
        toolbarBottom = true
        show(WindowInsets(left = 40.dp, bottom = 24.dp))
        val frame = bounds(FRAME)
        assertEquals(frame.bottom - px(24), bounds(PROGRESS).bottom, 0.5f)
        assertEquals(frame.left + px(40), bounds(TOOLBAR).left, 0.5f)
    }

    @Test
    fun theDefaultInsetsAvoidTheCutoutWhileTheChromeShows() {
        showStrip = true
        show()
        val cutout = px(40).toInt()
        rule.runOnUiThread { dispatchLeftCutout(hostView!!, cutout) }
        rule.waitForIdle()
        val frame = bounds(FRAME)
        for (tag in listOf(STRIP, TOOLBAR, PAGE)) assertEquals(tag, frame.left + cutout, bounds(tag).left, 0.5f)
    }

    @Test
    fun aFullscreenPageIgnoresTheCutout() {
        showChrome = false
        show()
        val cutout = px(40).toInt()
        rule.runOnUiThread { dispatchLeftCutout(hostView!!, cutout) }
        rule.waitForIdle()
        assertEquals(bounds(FRAME), bounds(PAGE))
    }

    private companion object {
        const val FRAME = "frame"
        const val STRIP = "strip"
        const val TOOLBAR = "toolbar"
        const val PROGRESS = "progress"
        const val PAGE = "page"
    }
}

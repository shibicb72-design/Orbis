package com.shibi.orbis.diagnostics

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class VideoDiagnosticsScreenTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun show(h: DiagHarness) {
        rule.setContent { MaterialTheme { VideoDiagnosticsScreen(diagnostics = h.diag, onBack = {}, clock = h.clock) } }
    }

    private fun switch(title: String) = rule.onNode(isToggleable() and hasText(title, substring = true))

    @Test
    fun theExtraSwitchesWorkOnlyWhileRecordingIsOn() {
        val h = DiagHarness().apply { diag.start() }
        show(h)
        rule.onNodeWithText("Video diagnostics helps find out why a video stops or will not play.", substring = true).assertExists()
        rule.onNodeWithText("The log is empty.").assertExists()
        switch("Record video diagnostics").assertIsOff().assertIsEnabled()
        switch("Include site names").assertIsNotEnabled()
        switch("Also record private tabs").assertIsNotEnabled()
        switch("Record video diagnostics").performClick()
        switch("Record video diagnostics").assertIsOn()
        switch("Include site names").assertIsEnabled().performClick()
        switch("Also record private tabs").assertIsEnabled().performClick()
        assertTrue(h.diag.switches.value.includeSiteNames)
        assertTrue(h.diag.switches.value.privateTabs)
        switch("Record video diagnostics").performClick()
        switch("Also record private tabs").assertIsOff().assertIsNotEnabled()
        switch("Include site names").assertIsNotEnabled()
        assertFalse(h.diag.switches.value.privateTabs)
        rule.onNodeWithText("Recording is off. The log stays here until you delete it, or for 7 days after its last entry.").assertExists()
    }

    @Test
    fun copyPutsExactlyTheShownTextOnTheClipboard() {
        val h = DiagHarness().apply { diag.start() }
        h.diag.setRecording(true)
        h.diag.onPageMessage(FakeTab(), Any(), media(1))
        show(h)
        val last = h.log.lines().last()
        rule.onNodeWithTag("video_diag_list").performScrollToNode(hasText(last))
        rule.onNodeWithText(last).assertExists()
        rule.onNodeWithTag("video_diag_list").performScrollToNode(hasText("Copy"))
        rule.onNodeWithText("Copy").performClick()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(h.log.lines().joinToString("\n"), clip.getItemAt(0).text.toString())
    }

    @Test
    fun deleteLogEmptiesItAndANewHeaderFollowsOnlyWhileRecording() {
        val h = DiagHarness().apply { diag.start() }
        h.diag.setRecording(true)
        h.diag.onPageMessage(FakeTab(), Any(), media(1))
        show(h)
        rule.onNodeWithTag("video_diag_list").performScrollToNode(hasText("Delete log"))
        rule.onNodeWithText("Delete log").performClick()
        rule.waitForIdle()
        assertTrue(h.log.lines().isNotEmpty())
        assertTrue(h.log.lines().all { it.startsWith("# ") })
        h.diag.setRecording(false)
        rule.waitForIdle()
        rule.onNodeWithTag("video_diag_list").performScrollToNode(hasText("Delete log"))
        rule.onNodeWithText("Delete log").performClick()
        rule.waitForIdle()
        assertTrue(h.log.isEmpty)
        rule.onNodeWithText("The log is empty.").assertExists()
    }

    /** The system file picker, answered at once with [picked] (null: the user cancelled). */
    private fun picker(picked: Uri?, names: MutableList<Any?>) =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                        names += input
                        dispatchResult(requestCode, picked)
                    }
                }
        }

    /** Taps "Save as text file" with a picker that returns [picked] and a writer that answers [writes]. */
    private fun save(picked: Uri?, writes: Boolean): Pair<DiagHarness, List<String>> {
        val h = DiagHarness().apply { diag.start() }
        h.diag.setRecording(true)
        val written = mutableListOf<String>()
        val names = mutableListOf<Any?>()
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker(picked, names)) {
                MaterialTheme {
                    VideoDiagnosticsScreen(diagnostics = h.diag, onBack = {}, clock = h.clock, writeFile = { _, _, text ->
                        written += text
                        writes
                    })
                }
            }
        }
        rule.onNodeWithTag("video_diag_list").performScrollToNode(hasText("Save as text file"))
        rule.onNodeWithText("Save as text file").performClick()
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        if (picked != null) assertEquals(listOf<Any?>("orbis-video-diagnostics-2026-11-23.txt"), names)
        return h to written
    }

    @Test
    fun aSavedFileSaysSavedAndHoldsExactlyTheShownText() {
        val (h, written) = save(Uri.parse("content://picked/orbis-video-diagnostics-2026-11-23.txt"), writes = true)
        assertEquals(listOf(h.log.lines().joinToString("\n")), written)
        assertEquals("Saved", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aWriteErrorSaysCouldNotSaveTheFile() {
        save(Uri.parse("content://picked/readonly.txt"), writes = false)
        assertEquals("Could not save the file.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aCancelledPickerSaysNothing() {
        val (_, written) = save(null, writes = true)
        assertTrue(written.isEmpty())
        assertEquals(0, ShadowToast.shownToastCount())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun saveSuggestsTheDatedName() {
        assertEquals("orbis-video-diagnostics-2026-11-23.txt", videoDiagFileName(MutableClock()))
    }
}

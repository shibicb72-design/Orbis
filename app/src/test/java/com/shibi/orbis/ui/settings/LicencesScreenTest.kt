package com.shibi.orbis.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import com.shibi.orbis.about.Licences
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LicencesScreenTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun listsEightComponentsAndExpandsTheLibtorrentText() {
        rule.setContent { MaterialTheme { LicencesScreen(onBack = {}) } }
        rule.waitForIdle()
        assertEquals(8, Licences.entries.size)
        for (entry in Licences.entries) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(entry.name))
            rule.onNodeWithText(entry.name).assertExists()
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("libtorrent"))
        rule.onAllNodesWithText("Redistribution and use", substring = true).assertCountEquals(0)
        rule.onNodeWithText("libtorrent").performClick()
        // The text is read on the IO dispatcher.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Redistribution and use", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val text = rule.onNodeWithText("Redistribution and use", substring = true).fetchSemanticsNode().config
        val shown = text[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(shown.startsWith("Copyright"))
    }
}

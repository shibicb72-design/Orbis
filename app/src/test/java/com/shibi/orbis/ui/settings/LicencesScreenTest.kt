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
    fun listsFourteenComponentsAndExpandsTheLibtorrentText() {
        rule.setContent { MaterialTheme { LicencesScreen(onBack = {}) } }
        rule.waitForIdle()
        assertEquals(14, Licences.entries.size)
        for (entry in Licences.entries) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(entry.name))
            rule.onNodeWithText(entry.name).assertExists()
        }
        // The MPL-2.0 components inside the natives say where their source is, at the shipped commit.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("libdatachannel"))
        rule.onNodeWithText("Version 0.24.1 + 2 commits (a4d3211) · Mozilla Public License 2.0").assertExists()
        rule.onNodeWithText("Source code: https://github.com/paullouisageneau/libdatachannel/tree/a4d3211da33bc1114ae50d2dd12f4186b67aaba4").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("libjuice"))
        rule.onNodeWithText("Source code: https://github.com/paullouisageneau/libjuice/tree/5948a4162d37bc213d6051b67ee2876ccc5a99a6").assertExists()
        // The YouTube picture-in-picture fix (MPL-2.0) points at the Firefox file it is adapted from.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Firefox webcompat: YouTube picture-in-picture fix"))
        rule.onNodeWithText(
            "Source code: https://github.com/mozilla-firefox/firefox/blob/e98e47c20429bdabcd34f5b550bdbcb2d432a0a2/browser/extensions/webcompat/" +
                "injections/js/bug1956165-www.youtube.com-picture-in-picture-fix.js",
        ).assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("libtorrent"))
        rule.onAllNodesWithText("Redistribution and use", substring = true).assertCountEquals(0)
        rule.onNodeWithText("libtorrent").performClick()
        // The text is read on the IO dispatcher.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Redistribution and use", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The route.h notice inside the file says it too, in its own preformatted block.
        val text = rule.onNodeWithText("Redistribution and use in source and binary forms, with or without modification", substring = true).fetchSemanticsNode().config
        val shown = text[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        // Reflowed for the screen: the sentence is one paragraph, no longer broken at the asset's 72 columns.
        assertTrue(shown.startsWith("Redistribution and use in source and binary forms, with or without modification, are permitted"))
        rule.onNodeWithText("Copyright (c) 2003-2020, Arvid Norberg\nAll rights reserved.").assertExists()
        // Each condition is one wrapped bullet, not a preformatted block cut off at the right edge.
        rule.onNodeWithText("Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.").assertExists()
    }
}

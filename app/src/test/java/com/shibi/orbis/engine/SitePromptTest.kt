package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePromptTest {
    private fun confirm(results: MutableList<Boolean?>) = SitePrompt.Confirm("example.com", "ok?") { results += it }

    @Test
    fun answersAtMostOnce() {
        val results = mutableListOf<Boolean?>()
        val p = confirm(results)
        p.accept()
        p.reject()
        p.dismiss()
        p.accept()
        assertEquals(listOf<Boolean?>(true), results)
        assertTrue(p.isFinished)
    }

    @Test
    fun dismissThenAnswerCompletesOnlyOnceAsDismissed() {
        val results = mutableListOf<Boolean?>()
        val p = confirm(results)
        p.dismiss()
        p.accept()
        assertEquals(listOf<Boolean?>(null), results)
    }

    @Test
    fun everyKindCompletesOnDismiss() {
        var completions = 0
        val prompts =
            listOf(
                SitePrompt.Alert("h", "m") { completions++ },
                SitePrompt.Confirm("h", "m") { completions++ },
                SitePrompt.TextInput("h", "m", "d") { completions++ },
                SitePrompt.Choice("h", "m", false, emptyList()) { completions++ },
                SitePrompt.Auth("h", "m", false, false, "") { completions++ },
                SitePrompt.BeforeUnload("h") { completions++ },
                SitePrompt.Repost("h") { completions++ },
                SitePrompt.FilePick("h", emptyList(), false) { completions++ },
            )
        prompts.forEach { it.dismiss(); it.dismiss() }
        assertEquals(prompts.size, completions)
    }

    @Test
    fun emptySelectionDismissesInsteadOfAnsweringWithNothing() {
        var result: List<String>? = listOf("sentinel")
        val p = SitePrompt.Choice("h", "m", true, emptyList()) { result = it }
        p.submit(emptyList())
        assertNull(result)
        assertTrue(p.isFinished)
    }

    @Test
    fun authReturnsCredentials() {
        var creds: AuthCredentials? = null
        SitePrompt.Auth("h", "m", false, false, "u") { creds = it }.submit("alice", "pw")
        assertEquals(AuthCredentials("alice", "pw"), creds)
    }

    @Test
    fun queueShowsOldestFirstAndAdvancesWhenFinished() {
        val q = PromptQueue()
        val a = SitePrompt.Alert("h", "a") {}
        val b = SitePrompt.Alert("h", "b") {}
        assertNull(q.head.value)
        q.add(a)
        q.add(b)
        assertSame(a, q.head.value)
        a.acknowledge()
        assertSame(b, q.head.value)
        b.dismiss()
        assertNull(q.head.value)
        assertEquals(0, q.size)
    }

    @Test
    fun dismissAllCompletesEverythingExactlyOnce() {
        val q = PromptQueue()
        val counts = IntArray(3)
        val prompts = counts.indices.map { i -> SitePrompt.Alert("h", "m") { counts[i]++ } }
        prompts.forEach(q::add)
        // One was already answered by the user.
        prompts[1].acknowledge()
        q.dismissAll()
        q.dismissAll()
        assertEquals(listOf(1, 1, 1), counts.toList())
        assertNull(q.head.value)
    }

    @Test
    fun finishedPromptIsNotQueued() {
        val q = PromptQueue()
        val p = SitePrompt.Alert("h", "m") {}
        p.dismiss()
        q.add(p)
        assertNull(q.head.value)
    }

    @Test
    fun flattenChoicesFlattensGroupsAndRespectsDisabled() {
        val nodes =
            listOf(
                ChoiceNode("a", "A", disabled = false, selected = true, separator = false, children = null),
                ChoiceNode(null, "Group", disabled = false, selected = false, separator = false, children = listOf(
                    ChoiceNode("g1", "G1", disabled = false, selected = false, separator = false, children = null),
                    ChoiceNode("g2", "G2", disabled = true, selected = false, separator = false, children = null),
                    ChoiceNode(null, "Inner", disabled = false, selected = false, separator = false, children = listOf(
                        ChoiceNode("i1", "I1", disabled = false, selected = false, separator = false, children = null),
                    )),
                )),
                ChoiceNode(null, "", disabled = false, selected = false, separator = true, children = null),
                ChoiceNode(null, "Off", disabled = true, selected = false, separator = false, children = listOf(
                    ChoiceNode("o1", "O1", disabled = false, selected = false, separator = false, children = null),
                )),
                ChoiceNode(null, "No id", disabled = false, selected = false, separator = false, children = null),
            )
        val flat = flattenChoices(nodes)
        assertEquals(
            listOf("A", "Group", "G1", "G2", "Inner", "I1", "Off", "O1"),
            flat.map { it.label },
        )
        assertEquals(listOf(0, 0, 1, 1, 1, 2, 0, 1), flat.map { it.depth })
        assertEquals(listOf(false, true, false, false, true, false, true, false), flat.map { it.isGroupHeader })
        val byLabel = flat.associateBy { it.label }
        assertTrue(byLabel.getValue("A").selected)
        assertTrue(byLabel.getValue("G1").enabled)
        assertFalse(byLabel.getValue("G2").enabled)
        assertFalse("child of a disabled group", byLabel.getValue("O1").enabled)
        assertFalse("headers are not selectable", byLabel.getValue("Group").enabled)
    }

    @Test
    fun pickerMimeTypesKeepsOnlyRealMimeTypes() {
        assertEquals(listOf("*/*"), pickerMimeTypes(emptyList()))
        assertEquals(listOf("*/*"), pickerMimeTypes(listOf(".pdf", "", "  ", "image")))
        assertEquals(listOf("image/*", "application/pdf"), pickerMimeTypes(listOf("image/*", ".pdf", "application/pdf", "image/*")))
    }
}

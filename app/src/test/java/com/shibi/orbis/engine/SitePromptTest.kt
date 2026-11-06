package com.shibi.orbis.engine

import android.net.Uri
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
                SitePrompt.FilePick("h", emptyList(), false, prepare = {}, respond = { completions++ }),
                SitePrompt.DateTime("h", DateTimeKind.DATE, "", "", "", "") { completions++ },
                SitePrompt.ColorPick("h", "#000000", emptyList()) { completions++ },
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
    fun pickerMimeTypesKeepsMimeTypesAndIgnoresBlanks() {
        assertEquals(listOf("*/*"), pickerMimeTypes(emptyList()))
        assertEquals(listOf("*/*"), pickerMimeTypes(listOf("", "  ")))
        assertEquals(listOf("image/*", "application/pdf"), pickerMimeTypes(listOf(" image/* ", "", "application/pdf", "image/*")))
    }

    @Test
    fun pickerMimeTypesMapsExtensionsLowercasedWithoutDot() {
        val seen = mutableListOf<String>()
        val lookup = { ext: String ->
            seen += ext
            mapOf("pdf" to "application/pdf", "jpg" to "image/jpeg")[ext]
        }
        assertEquals(listOf("application/pdf", "image/jpeg"), pickerMimeTypes(listOf(".PDF", " .jpg "), lookup))
        assertEquals(listOf("pdf", "jpg"), seen)
        // Duplicates collapse, also across the two spellings.
        assertEquals(listOf("image/jpeg"), pickerMimeTypes(listOf(".jpg", "image/jpeg"), lookup))
    }

    @Test
    fun pickerMimeTypesFallsBackToAnythingWhenATokenCannotBeMapped() {
        // Dropping ".xyz" would stop the user choosing the very file the page accepts.
        assertEquals(listOf("*/*"), pickerMimeTypes(listOf("image/*", ".xyz")))
        assertEquals(listOf("*/*"), pickerMimeTypes(listOf(".pdf")))
        assertEquals(listOf("*/*"), pickerMimeTypes(listOf("image")))
        assertEquals(listOf("*/*"), pickerMimeTypes(listOf("a/b/c")))
        assertEquals(listOf("*/*"), pickerMimeTypes(listOf("image/ png")))
        assertEquals(listOf("*/*"), pickerMimeTypes(listOf(".")))
        assertEquals(listOf("*/*"), pickerMimeTypes(listOf(".bad"), { "notamime" }))
    }

    @Test
    fun dateTimeSubmitClearAndDismiss() {
        val got = mutableListOf<String?>()
        SitePrompt.DateTime("h", DateTimeKind.TIME, "10:00", "", "", "") { got += it }.submit("11:30")
        SitePrompt.DateTime("h", DateTimeKind.TIME, "10:00", "", "", "") { got += it }.clear()
        SitePrompt.DateTime("h", DateTimeKind.TIME, "10:00", "", "", "") { got += it }.dismiss()
        assertEquals(listOf<String?>("11:30", "", null), got)
        val once = mutableListOf<String?>()
        val p = SitePrompt.DateTime("h", DateTimeKind.DATE, "", "", "", "") { once += it }
        p.submit("2026-01-01")
        p.clear()
        p.dismiss()
        assertEquals(listOf<String?>("2026-01-01"), once)
    }

    @Test
    fun colorPickSubmitAndDismissAreExactlyOnce() {
        val got = mutableListOf<String?>()
        val p = SitePrompt.ColorPick("h", "#000000", listOf("#ff0000")) { got += it }
        p.submit("#112233")
        p.submit("#445566")
        p.dismiss()
        assertEquals(listOf<String?>("#112233"), got)
        val d = mutableListOf<String?>()
        val q = SitePrompt.ColorPick("h", "#000000", emptyList()) { d += it }
        q.dismiss()
        q.submit("#112233")
        assertEquals(listOf<String?>(null), d)
    }

    // ---- FilePick stages ----

    private class FileHarness(multiple: Boolean = false) {
        val prepared = mutableListOf<List<Uri>>()
        val responses = mutableListOf<List<Uri>?>()
        val prompt =
            SitePrompt.FilePick("h", listOf("*/*"), multiple, prepare = { prepared += it }, respond = { responses += it })
    }

    /**
     * Placeholders typed as Uri. The JVM android.jar cannot build a Uri (every method throws), and FilePick only passes
     * the list along without reading an element, so erased plain objects are enough here.
     */
    @Suppress("UNCHECKED_CAST")
    private fun uris(n: Int = 1): List<Uri> = List(n) { Any() } as List<Uri>

    @Test
    fun filePickStartsPickingAndClaimsLaunchOnce() {
        val h = FileHarness()
        assertEquals(FilePickStage.PICKING, h.prompt.stage.value)
        assertTrue(h.prompt.claimPickerLaunch())
        assertFalse(h.prompt.claimPickerLaunch())
        assertFalse(h.prompt.claimPickerLaunch())
        // A different prompt has its own claim.
        assertTrue(FileHarness().prompt.claimPickerLaunch())
    }

    @Test
    fun filePickHappyPathPickingPreparingReady() {
        val h = FileHarness()
        val picked = uris()
        h.prompt.submit(picked)
        assertEquals(FilePickStage.PREPARING, h.prompt.stage.value)
        assertEquals(listOf(picked), h.prepared)
        assertFalse("still queued while copying", h.prompt.isFinished)
        assertTrue(h.responses.isEmpty())
        val files = uris(2)
        h.prompt.filesReady(files)
        assertTrue(h.prompt.isFinished)
        assertEquals(listOf<List<Uri>?>(files), h.responses)
    }

    @Test
    fun filePickEmptySubmitDismisses() {
        val h = FileHarness()
        h.prompt.submit(emptyList())
        assertTrue(h.prompt.isFinished)
        assertEquals(listOf<List<Uri>?>(null), h.responses)
        assertTrue(h.prepared.isEmpty())
    }

    @Test
    fun filePickEmptyReadyDismisses() {
        val h = FileHarness()
        h.prompt.submit(uris())
        h.prompt.filesReady(emptyList())
        assertTrue(h.prompt.isFinished)
        assertEquals(listOf<List<Uri>?>(null), h.responses)
    }

    @Test
    fun filePickSecondSubmitWhilePreparingIsIgnored() {
        val h = FileHarness()
        h.prompt.submit(uris())
        h.prompt.submit(uris())
        assertEquals(1, h.prepared.size)
        assertEquals(FilePickStage.PREPARING, h.prompt.stage.value)
    }

    @Test
    fun filePickDismissDuringPreparingCompletesOnceAndLateFilesAreIgnored() {
        val h = FileHarness()
        h.prompt.submit(uris())
        h.prompt.dismiss()
        assertTrue(h.prompt.isFinished)
        h.prompt.filesReady(uris())
        h.prompt.prepareFailed()
        h.prompt.dismiss()
        assertEquals(listOf<List<Uri>?>(null), h.responses)
    }

    @Test
    fun filePickFilesReadyAfterDismissIsNoOp() {
        val h = FileHarness()
        h.prompt.dismiss()
        h.prompt.filesReady(uris())
        assertEquals(listOf<List<Uri>?>(null), h.responses)
    }

    @Test
    fun filePickSubmitAfterDismissIsNoOp() {
        val h = FileHarness()
        h.prompt.dismiss()
        h.prompt.submit(uris())
        assertTrue(h.prepared.isEmpty())
        assertEquals(listOf<List<Uri>?>(null), h.responses)
    }

    @Test
    fun filePickFailureShowsErrorStageThenOkDismisses() {
        val h = FileHarness()
        h.prompt.submit(uris())
        h.prompt.prepareFailed()
        assertEquals(FilePickStage.FAILED, h.prompt.stage.value)
        assertFalse("stays queued until the user acknowledges", h.prompt.isFinished)
        assertTrue(h.responses.isEmpty())
        h.prompt.submit(uris())
        assertEquals("no retry from FAILED", 1, h.prepared.size)
        h.prompt.dismiss()
        assertEquals(listOf<List<Uri>?>(null), h.responses)
    }

    @Test
    fun filePickPrepareFailedAfterFinishKeepsStage() {
        val h = FileHarness()
        h.prompt.submit(uris())
        h.prompt.filesReady(uris())
        h.prompt.prepareFailed()
        assertEquals(FilePickStage.PREPARING, h.prompt.stage.value)
    }

    @Test
    fun filePickPrepareThatThrowsBecomesFailed() {
        val responses = mutableListOf<List<Uri>?>()
        val p = SitePrompt.FilePick("h", emptyList(), false, prepare = { error("boom") }, respond = { responses += it })
        p.submit(uris())
        assertEquals(FilePickStage.FAILED, p.stage.value)
        assertFalse(p.isFinished)
        assertTrue(responses.isEmpty())
    }

    @Test
    fun filePickLeavesQueueOnlyWhenFinishedAndBlocksLaterPromptsMeanwhile() {
        val q = PromptQueue()
        val h = FileHarness()
        val later = SitePrompt.Alert("h", "later") {}
        q.add(h.prompt)
        q.add(later)
        h.prompt.submit(uris())
        assertSame("later prompts wait while copying", h.prompt, q.head.value)
        h.prompt.filesReady(uris())
        assertSame(later, q.head.value)
    }
}

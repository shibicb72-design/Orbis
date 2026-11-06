package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePermissionPromptTest {
    private fun prompt(
        answers: MutableList<SitePermissionAnswer?>,
        embedder: String? = null,
        kinds: List<SitePermissionKind> = listOf(SitePermissionKind.LOCATION),
    ) = SitePrompt.SitePermission("https://example.com:8443", embedder, kinds) { answers += it }

    @Test
    fun allowAnswersOnceWithTheRememberChoice() {
        val answers = mutableListOf<SitePermissionAnswer?>()
        val p = prompt(answers)
        p.allow(remember = true)
        p.allow(remember = false)
        p.deny(remember = false)
        p.dismiss()
        assertEquals(listOf<SitePermissionAnswer?>(SitePermissionAnswer(allow = true, remember = true)), answers)
        assertTrue(p.isFinished)
    }

    @Test
    fun denyRecordsWhenAsked() {
        val answers = mutableListOf<SitePermissionAnswer?>()
        prompt(answers).deny(remember = false)
        assertEquals(listOf<SitePermissionAnswer?>(SitePermissionAnswer(allow = false, remember = false)), answers)
    }

    @Test
    fun dismissIsNullMeaningDenyWithoutRecording() {
        val answers = mutableListOf<SitePermissionAnswer?>()
        val p = prompt(answers)
        p.dismiss()
        p.dismiss()
        p.allow(remember = true)
        p.deny(remember = true)
        assertEquals(listOf<SitePermissionAnswer?>(null), answers)
        assertTrue(p.isFinished)
    }

    @Test
    fun originHostComesFromTheOriginNotFromAnyPageText() {
        val p = prompt(mutableListOf())
        assertEquals("example.com", p.originHost)
        assertEquals("https://example.com:8443", p.origin)
    }

    @Test
    fun carriesEmbedderAndKinds() {
        val p = prompt(mutableListOf(), embedder = "https://top.example", kinds = listOf(SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE))
        assertEquals("https://top.example", p.embedder)
        assertEquals(listOf(SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE), p.kinds)
    }

    @Test
    fun leavesTheQueueWhenAnswered() {
        val q = PromptQueue()
        val p = prompt(mutableListOf())
        q.add(p)
        assertSame(p, q.head.value)
        p.allow(remember = false)
        assertNull(q.head.value)
    }

    @Test
    fun dismissAllCompletesIt() {
        val answers = mutableListOf<SitePermissionAnswer?>()
        val q = PromptQueue()
        q.add(prompt(answers))
        q.dismissAll()
        assertEquals(listOf<SitePermissionAnswer?>(null), answers)
    }
}

class PromptQueueDismissWhereTest {
    @Test
    fun dismissesOnlyTheSelectedPromptsAndKeepsTheOrder() {
        val q = PromptQueue()
        val alertCount = IntArray(1)
        val answers = mutableListOf<SitePermissionAnswer?>()
        val alert = SitePrompt.Alert("h", "m") { alertCount[0]++ }
        val perm1 = SitePrompt.SitePermission("https://a.example", null, listOf(SitePermissionKind.LOCATION)) { answers += it }
        val confirmResults = mutableListOf<Boolean?>()
        val confirm = SitePrompt.Confirm("h", "m") { confirmResults += it }
        val perm2 = SitePrompt.SitePermission("https://a.example", null, listOf(SitePermissionKind.CAMERA)) { answers += it }
        listOf(alert, perm1, confirm, perm2).forEach(q::add)

        q.dismissWhere { it is SitePrompt.SitePermission }

        assertEquals(listOf<SitePermissionAnswer?>(null, null), answers)
        assertEquals(2, q.size)
        assertSame(alert, q.head.value)
        assertEquals(0, alertCount[0])
        assertTrue(confirmResults.isEmpty())
        alert.acknowledge()
        assertSame(confirm, q.head.value)
    }

    @Test
    fun theHeadCanBeTheOneDismissed() {
        val q = PromptQueue()
        val answers = mutableListOf<SitePermissionAnswer?>()
        val perm = SitePrompt.SitePermission("https://a.example", null, listOf(SitePermissionKind.LOCATION)) { answers += it }
        val alert = SitePrompt.Alert("h", "m") {}
        q.add(perm)
        q.add(alert)
        q.dismissWhere { it is SitePrompt.SitePermission }
        assertSame(alert, q.head.value)
        assertEquals(listOf<SitePermissionAnswer?>(null), answers)
    }

    @Test
    fun nothingSelectedChangesNothing() {
        val q = PromptQueue()
        val alert = SitePrompt.Alert("h", "m") {}
        q.add(alert)
        q.dismissWhere { false }
        assertSame(alert, q.head.value)
        assertFalse(alert.isFinished)
    }

    @Test
    fun anAlreadyAnsweredPromptIsNotCompletedAgain() {
        val q = PromptQueue()
        val answers = mutableListOf<SitePermissionAnswer?>()
        val perm = SitePrompt.SitePermission("https://a.example", null, listOf(SitePermissionKind.LOCATION)) { answers += it }
        q.add(perm)
        perm.allow(remember = false)
        q.dismissWhere { true }
        q.dismissWhere { true }
        assertEquals(listOf<SitePermissionAnswer?>(SitePermissionAnswer(true, false)), answers)
    }

    @Test
    fun emptyQueueIsFine() {
        val q = PromptQueue()
        q.dismissWhere { true }
        assertEquals(0, q.size)
    }
}

package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanMediaRequestTest {
    private val cam = SitePermissionKind.CAMERA
    private val mic = SitePermissionKind.MICROPHONE

    private fun plan(kinds: Set<SitePermissionKind>, stored: Map<SitePermissionKind, Boolean>) =
        planMediaRequest(kinds) { stored[it] }

    @Test
    fun nothingStoredAsks() {
        assertEquals(MediaPlan.ASK, plan(setOf(cam), emptyMap()))
        assertEquals(MediaPlan.ASK, plan(setOf(cam, mic), emptyMap()))
    }

    @Test
    fun everythingAllowedGrants() {
        assertEquals(MediaPlan.GRANT, plan(setOf(cam), mapOf(cam to true)))
        assertEquals(MediaPlan.GRANT, plan(setOf(cam, mic), mapOf(cam to true, mic to true)))
    }

    @Test
    fun anyStoredDenyDenies() {
        assertEquals(MediaPlan.DENY, plan(setOf(cam), mapOf(cam to false)))
        assertEquals(MediaPlan.DENY, plan(setOf(cam, mic), mapOf(cam to true, mic to false)))
        assertEquals(MediaPlan.DENY, plan(setOf(cam, mic), mapOf(cam to false)))
    }

    @Test
    fun partlyAllowedAsks() {
        assertEquals(MediaPlan.ASK, plan(setOf(cam, mic), mapOf(cam to true)))
    }

    @Test
    fun onlyRequestedKindsAreConsulted() {
        // A stored deny for a kind that is not requested does not matter.
        assertEquals(MediaPlan.GRANT, plan(setOf(mic), mapOf(mic to true, cam to false)))
    }

    @Test
    fun nothingRequestedDenies() {
        assertEquals(MediaPlan.DENY, plan(emptySet(), emptyMap()))
    }
}

class PermissionPromptCoalescerTest {
    @Test
    fun firstJoinOwnsTheDialogLaterOnesJoin() {
        val c = PermissionPromptCoalescer<String>()
        assertTrue(c.join("k") {})
        assertFalse(c.join("k") {})
        assertFalse(c.join("k") {})
        assertTrue(c.isPending("k"))
    }

    @Test
    fun oneAnswerCompletesEveryWaiterOnce() {
        val c = PermissionPromptCoalescer<String>()
        val got = mutableListOf<String>()
        c.join("k") { got += "a:$it" }
        c.join("k") { got += "b:$it" }
        c.join("k") { got += "c:$it" }
        c.answer("k", true)
        assertEquals(listOf("a:true", "b:true", "c:true"), got)
        assertFalse(c.isPending("k"))
    }

    @Test
    fun answeringTwiceDoesNothingTheSecondTime() {
        val c = PermissionPromptCoalescer<String>()
        var count = 0
        c.join("k") { count++ }
        c.answer("k", false)
        c.answer("k", true)
        c.answer("never-joined", true)
        assertEquals(1, count)
    }

    @Test
    fun differentKeysAreIndependent() {
        val c = PermissionPromptCoalescer<String>()
        val got = mutableListOf<String>()
        assertTrue(c.join("a") { got += "a:$it" })
        assertTrue(c.join("b") { got += "b:$it" })
        c.answer("b", false)
        assertEquals(listOf("b:false"), got)
        assertTrue(c.isPending("a"))
        c.answer("a", true)
        assertEquals(listOf("b:false", "a:true"), got)
    }

    @Test
    fun keyIsReusableAfterItWasAnswered() {
        val c = PermissionPromptCoalescer<String>()
        assertTrue(c.join("k") {})
        c.answer("k", true)
        assertTrue("a later request needs a fresh dialog", c.join("k") {})
    }

    @Test
    fun aThrowingWaiterDoesNotStopTheOthers() {
        val c = PermissionPromptCoalescer<String>()
        val got = mutableListOf<Boolean>()
        c.join("k") { throw IllegalStateException("session closed") }
        c.join("k") { got += it }
        c.answer("k", false)
        assertEquals(listOf(false), got)
    }

    @Test
    fun structuredKeysCoalesceByValue() {
        val c = PermissionPromptCoalescer<SitePermissionKey>()
        val a = SitePermissionKey(SitePermissionKind.LOCATION, "https://a.example")
        val same = SitePermissionKey(SitePermissionKind.LOCATION, "https://a.example")
        val framed = SitePermissionKey(SitePermissionKind.LOCATION, "https://a.example", "https://top.example")
        assertTrue(c.join(a) {})
        assertFalse(c.join(same) {})
        assertTrue("same origin as a frame is a different question", c.join(framed) {})
    }
}

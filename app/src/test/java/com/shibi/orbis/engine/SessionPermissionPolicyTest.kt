package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPermissionPolicyTest {
    private val location = SitePermissionKey(SitePermissionKind.LOCATION, "https://a.example")
    private val camera = SitePermissionKey(SitePermissionKind.CAMERA, "https://a.example")

    private class Store(val stored: Map<SitePermissionKey, Boolean> = emptyMap()) {
        val lookups = mutableListOf<SitePermissionKey>()
        val records = mutableListOf<Triple<SitePermissionKey, Boolean, Boolean>>()

        fun policy(isPrivate: Boolean) =
            SessionPermissionPolicy(
                isPrivate,
                lookup = { lookups += it; stored[it] },
                record = { key, allow, remember -> records += Triple(key, allow, remember) },
            )
    }

    @Test
    fun aNormalSessionDelegatesLookupAndRecord() {
        val store = Store(mapOf(location to true, camera to false))
        val policy = store.policy(isPrivate = false)
        assertEquals(true, policy.lookup(location))
        assertEquals(false, policy.lookup(camera))
        assertNull(policy.lookup(SitePermissionKey(SitePermissionKind.MICROPHONE, "https://a.example")))
        policy.record(location, true, true)
        policy.record(camera, false, false)
        assertEquals(listOf(Triple(location, true, true), Triple(camera, false, false)), store.records)
        assertTrue(policy.offerRemember)
    }

    @Test
    fun aPrivateSessionIgnoresStoredAnswersAndAlwaysAsks() {
        val store = Store(mapOf(location to true, camera to false))
        val policy = store.policy(isPrivate = true)
        assertNull(policy.lookup(location))
        assertNull(policy.lookup(camera))
        // The stored tiers are not even consulted.
        assertTrue(store.lookups.isEmpty())
    }

    @Test
    fun aPrivateSessionNeverRecordsAndDoesNotOfferToRemember() {
        val store = Store()
        val policy = store.policy(isPrivate = true)
        policy.record(location, true, true)
        policy.record(camera, false, false)
        assertTrue(store.records.isEmpty())
        assertFalse(policy.offerRemember)
    }

    @Test
    fun aPrivateMediaRequestPlansToAskEvenWithAStoredAllow() {
        val store = Store(mapOf(camera to true))
        val policy = store.policy(isPrivate = true)
        val plan = planMediaRequest(setOf(SitePermissionKind.CAMERA)) { kind -> policy.lookup(SitePermissionKey(kind, "https://a.example")) }
        assertEquals(MediaPlan.ASK, plan)
        val normal = store.policy(isPrivate = false)
        val normalPlan = planMediaRequest(setOf(SitePermissionKind.CAMERA)) { kind -> normal.lookup(SitePermissionKey(kind, "https://a.example")) }
        assertEquals(MediaPlan.GRANT, normalPlan)
    }

    @Test
    fun theRememberChoiceIsOfferedByDefault() {
        val answers = mutableListOf<SitePermissionAnswer?>()
        val prompt = SitePrompt.SitePermission("https://a.example", null, listOf(SitePermissionKind.LOCATION)) { answers += it }
        assertTrue(prompt.offerRemember)
        val privatePrompt =
            SitePrompt.SitePermission(
                "https://a.example",
                null,
                listOf(SitePermissionKind.LOCATION),
                offerRemember = false,
            ) { answers += it }
        assertFalse(privatePrompt.offerRemember)
        privatePrompt.allow(remember = false)
        assertEquals(listOf<SitePermissionAnswer?>(SitePermissionAnswer(allow = true, remember = false)), answers)
    }
}

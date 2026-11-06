package com.shibi.orbis.engine

import com.shibi.orbis.engine.AndroidPermissionBroker.Companion.CAMERA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BrokerEnsureTest {
    private class EnsureAsked : AskedPermissions {
        private val asked = HashSet<String>()

        override fun wasAsked(permission: String) = permission in asked

        override fun markAsked(permission: String) {
            asked += permission
        }
    }

    private var clock = 5_000L
    private val broker = AndroidPermissionBroker(EnsureAsked()) { clock }
    private val results = mutableListOf<Boolean>()

    private fun ensure(granted: Boolean) = broker.ensure(listOf(CAMERA), { granted }) { results += it }

    @Test
    fun alreadyGrantedAnswersTrueWithNoDialog() {
        ensure(granted = true)
        assertEquals(listOf(true), results)
        assertNull(broker.head.value)
    }

    @Test
    fun alreadyGrantedLeavesNothingPending() {
        ensure(granted = true)
        // A later request is the first in the queue: nothing was left behind.
        broker.request(listOf(CAMERA)) {}
        assertEquals(1L, broker.head.value!!.id)
    }

    @Test
    fun notGrantedPublishesARequestAndWaitsForTheResult() {
        ensure(granted = false)
        val head = broker.head.value
        assertNotNull(head)
        assertEquals(listOf(CAMERA), head!!.permissions)
        assertEquals(emptyList<Boolean>(), results)

        broker.finish(head, mapOf(CAMERA to true), blocked = false)
        assertEquals(listOf(true), results)
        assertNull(broker.head.value)
    }

    @Test
    fun deniedAnswersFalse() {
        ensure(granted = false)
        broker.finish(broker.head.value!!, mapOf(CAMERA to false), blocked = false)
        assertEquals(listOf(false), results)
        assertNull(broker.head.value)
    }

    @Test
    fun insideTheDenialCooldownAnswersFalseWithNoDialog() {
        ensure(granted = false)
        broker.finish(broker.head.value!!, mapOf(CAMERA to false), blocked = false)
        results.clear()

        clock += AndroidPermissionBroker.DENIAL_COOLDOWN_MS - 1
        ensure(granted = false)
        assertEquals(listOf(false), results)
        assertNull(broker.head.value)
    }

    @Test
    fun afterTheCooldownItAsksAgain() {
        ensure(granted = false)
        broker.finish(broker.head.value!!, mapOf(CAMERA to false), blocked = false)
        results.clear()

        clock += AndroidPermissionBroker.DENIAL_COOLDOWN_MS
        ensure(granted = false)
        assertNotNull(broker.head.value)
        assertEquals(emptyList<Boolean>(), results)
    }

    @Test
    fun grantedStateIsReadForTheRequestedPermissionsOnly() {
        val asked = mutableListOf<String>()
        broker.ensure(listOf(CAMERA), { asked += it; true }) { results += it }
        assertEquals(listOf(CAMERA), asked)
        assertEquals(listOf(true), results)
    }

    @Test
    fun aCallbackThatThrowsDoesNotEscape() {
        broker.ensure(listOf(CAMERA), { true }) { error("boom") }
        assertNull(broker.head.value)
    }

    @Test
    fun aDisallowedPermissionIsRefusedWithNoDialog() {
        broker.ensure(listOf("android.permission.READ_CONTACTS"), { false }) { results += it }
        assertEquals(listOf(false), results)
        assertNull(broker.head.value)
    }
}

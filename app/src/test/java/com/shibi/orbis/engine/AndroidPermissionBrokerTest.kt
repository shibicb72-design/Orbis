package com.shibi.orbis.engine

import com.shibi.orbis.engine.AndroidPermissionBroker.Companion.CAMERA
import com.shibi.orbis.engine.AndroidPermissionBroker.Companion.COARSE_LOCATION
import com.shibi.orbis.engine.AndroidPermissionBroker.Companion.DENIAL_COOLDOWN_MS
import com.shibi.orbis.engine.AndroidPermissionBroker.Companion.FINE_LOCATION
import com.shibi.orbis.engine.AndroidPermissionBroker.Companion.RECORD_AUDIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeAsked : AskedPermissions {
    val asked = HashSet<String>()

    override fun wasAsked(permission: String) = permission in asked

    override fun markAsked(permission: String) {
        asked += permission
    }
}

class AndroidPermissionBrokerTest {
    private var clock = 5_000L
    private val asked = FakeAsked()
    private val broker = AndroidPermissionBroker(asked) { clock }

    /** Records every completion of one request() call. */
    private class Results {
        val values = mutableListOf<Boolean>()
        val callback: (Boolean) -> Unit = { values += it }
    }

    private fun ask(vararg permissions: String): Results {
        val r = Results()
        broker.request(permissions.toList(), r.callback)
        return r
    }

    private fun denied(vararg permissions: String) = permissions.associateWith { false }

    private fun granted(vararg permissions: String) = permissions.associateWith { true }

    // ---- what may be requested ----

    @Test
    fun disallowedPermissionIsRejectedWithNoDialog() {
        val r = ask("android.permission.READ_CONTACTS")
        assertEquals(listOf(false), r.values)
        assertNull(broker.head.value)
    }

    @Test
    fun aDisallowedPermissionAmongAllowedOnesRejectsTheWholeRequest() {
        val r = ask(CAMERA, "android.permission.READ_SMS")
        assertEquals(listOf(false), r.values)
        assertNull(broker.head.value)
    }

    @Test
    fun emptyRequestIsRejectedWithNoDialog() {
        val r = ask()
        assertEquals(listOf(false), r.values)
        assertNull(broker.head.value)
    }

    @Test
    fun onlyTheFourPermissionsAreEverAllowed() {
        assertEquals(listOf(CAMERA, RECORD_AUDIO, FINE_LOCATION, COARSE_LOCATION), AndroidPermissionBroker.ALLOWED)
        for (p in listOf("android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.POST_NOTIFICATIONS", "", "CAMERA")) {
            assertEquals(listOf(false), ask(p).values)
            assertNull(broker.head.value)
        }
    }

    @Test
    fun locationAlwaysAsksFineAndCoarseTogether() {
        ask(FINE_LOCATION)
        assertEquals(listOf(FINE_LOCATION, COARSE_LOCATION), broker.head.value!!.permissions)
    }

    @Test
    fun coarseOnlyAlsoAsksBoth() {
        ask(COARSE_LOCATION)
        assertEquals(listOf(FINE_LOCATION, COARSE_LOCATION), broker.head.value!!.permissions)
    }

    @Test
    fun permissionsAreOrderedAndDeduplicated() {
        ask(RECORD_AUDIO, CAMERA, CAMERA)
        assertEquals(listOf(CAMERA, RECORD_AUDIO), broker.head.value!!.permissions)
    }

    // ---- coalescing and queue ----

    @Test
    fun identicalPendingRequestsShareOneSystemDialog() {
        val a = ask(CAMERA, RECORD_AUDIO)
        val b = ask(RECORD_AUDIO, CAMERA)
        val request = broker.head.value!!
        assertEquals(listOf(CAMERA, RECORD_AUDIO), request.permissions)

        broker.finish(request, granted(CAMERA, RECORD_AUDIO), blocked = false)

        assertEquals(listOf(true), a.values)
        assertEquals(listOf(true), b.values)
        assertNull("only one dialog was needed", broker.head.value)
    }

    @Test
    fun fineAndCoarseRequestsCoalesce() {
        val a = ask(FINE_LOCATION)
        val b = ask(COARSE_LOCATION)
        val c = ask(FINE_LOCATION, COARSE_LOCATION)
        val request = broker.head.value!!
        broker.finish(request, granted(FINE_LOCATION, COARSE_LOCATION), blocked = false)
        assertEquals(listOf(true), a.values)
        assertEquals(listOf(true), b.values)
        assertEquals(listOf(true), c.values)
        assertNull(broker.head.value)
    }

    @Test
    fun differentRequestsQueueAndTheHeadAdvances() {
        val a = ask(CAMERA)
        val b = ask(RECORD_AUDIO)
        val first = broker.head.value!!
        assertEquals(listOf(CAMERA), first.permissions)

        broker.finish(first, granted(CAMERA), blocked = false)
        assertEquals(listOf(true), a.values)
        assertEquals(emptyList<Boolean>(), b.values)
        val second = broker.head.value!!
        assertEquals(listOf(RECORD_AUDIO), second.permissions)

        broker.finish(second, granted(RECORD_AUDIO), blocked = false)
        assertEquals(listOf(true), b.values)
        assertNull(broker.head.value)
    }

    @Test
    fun launchIsClaimedOnlyOnce() {
        ask(CAMERA)
        val request = broker.head.value!!
        assertTrue(request.claimLaunch())
        assertFalse(request.claimLaunch())
    }

    // ---- results ----

    @Test
    fun grantedReportsTrue() {
        val r = ask(CAMERA)
        broker.finish(broker.head.value!!, granted(CAMERA), blocked = false)
        assertEquals(listOf(true), r.values)
    }

    @Test
    fun deniedReportsFalse() {
        val r = ask(CAMERA)
        broker.finish(broker.head.value!!, denied(CAMERA), blocked = false)
        assertEquals(listOf(false), r.values)
    }

    @Test
    fun cameraAndMicNeedBothToCountAsGranted() {
        val r = ask(CAMERA, RECORD_AUDIO)
        broker.finish(broker.head.value!!, mapOf(CAMERA to true, RECORD_AUDIO to false), blocked = false)
        assertEquals(listOf(false), r.values)
    }

    @Test
    fun coarseOnlyCountsAsGranted() {
        val r = ask(FINE_LOCATION)
        broker.finish(broker.head.value!!, mapOf(FINE_LOCATION to false, COARSE_LOCATION to true), blocked = false)
        assertEquals(listOf(true), r.values)
        // And it is not treated as a denial: asking again is allowed straight away.
        ask(FINE_LOCATION)
        assertNotNull(broker.head.value)
    }

    @Test
    fun fineOnlyCountsAsGranted() {
        val r = ask(COARSE_LOCATION)
        broker.finish(broker.head.value!!, mapOf(FINE_LOCATION to true, COARSE_LOCATION to false), blocked = false)
        assertEquals(listOf(true), r.values)
    }

    @Test
    fun bothLocationPermissionsDeniedIsFalse() {
        val r = ask(FINE_LOCATION)
        broker.finish(broker.head.value!!, denied(FINE_LOCATION, COARSE_LOCATION), blocked = false)
        assertEquals(listOf(false), r.values)
    }

    @Test
    fun missingResultsCountAsDenied() {
        val r = ask(CAMERA)
        broker.finish(broker.head.value!!, emptyMap(), blocked = false)
        assertEquals(listOf(false), r.values)
    }

    // ---- cool-down ----

    @Test
    fun aDenialCoolsDownFurtherRequestsForSixtySeconds() {
        ask(CAMERA)
        broker.finish(broker.head.value!!, denied(CAMERA), blocked = false)

        clock += DENIAL_COOLDOWN_MS - 1
        val early = ask(CAMERA)
        assertEquals("rejected without a dialog", listOf(false), early.values)
        assertNull(broker.head.value)

        clock += 1
        val later = ask(CAMERA)
        assertEquals(emptyList<Boolean>(), later.values)
        assertNotNull("the cool-down is over, so the system dialog is launched", broker.head.value)
    }

    @Test
    fun cooldownIsPerPermission() {
        ask(CAMERA)
        broker.finish(broker.head.value!!, denied(CAMERA), blocked = false)
        ask(RECORD_AUDIO)
        assertNotNull(broker.head.value)
    }

    @Test
    fun aRequestContainingACooledPermissionIsRejected() {
        ask(CAMERA)
        broker.finish(broker.head.value!!, denied(CAMERA), blocked = false)
        val r = ask(CAMERA, RECORD_AUDIO)
        assertEquals(listOf(false), r.values)
        assertNull(broker.head.value)
    }

    @Test
    fun deniedLocationCoolsDownBothLocationPermissions() {
        ask(FINE_LOCATION)
        broker.finish(broker.head.value!!, denied(FINE_LOCATION, COARSE_LOCATION), blocked = false)
        assertEquals(listOf(false), ask(FINE_LOCATION).values)
        assertEquals(listOf(false), ask(COARSE_LOCATION).values)
        assertNull(broker.head.value)
    }

    @Test
    fun afterTheCooldownAGrantLeavesNothingCooledDown() {
        ask(CAMERA)
        broker.finish(broker.head.value!!, denied(CAMERA), blocked = false)
        clock += DENIAL_COOLDOWN_MS
        ask(CAMERA)
        broker.finish(broker.head.value!!, granted(CAMERA), blocked = false)
        ask(CAMERA)
        assertNotNull(broker.head.value)
    }

    @Test
    fun aQueuedRequestForAJustDeniedPermissionIsRefusedWithoutADialog() {
        val both = ask(CAMERA, RECORD_AUDIO)
        val cameraOnly = ask(CAMERA)
        val micOnly = ask(RECORD_AUDIO)
        broker.finish(broker.head.value!!, denied(CAMERA, RECORD_AUDIO), blocked = false)

        assertEquals(listOf(false), both.values)
        assertEquals(listOf(false), cameraOnly.values)
        assertEquals(listOf(false), micOnly.values)
        assertNull(broker.head.value)
    }

    @Test
    fun aQueuedRequestForAnUnrelatedPermissionStillGetsItsDialog() {
        val camera = ask(CAMERA)
        val mic = ask(RECORD_AUDIO)
        broker.finish(broker.head.value!!, denied(CAMERA), blocked = false)
        assertEquals(listOf(false), camera.values)
        assertEquals(emptyList<Boolean>(), mic.values)
        assertEquals(listOf(RECORD_AUDIO), broker.head.value!!.permissions)
    }

    // ---- completion exactly once ----

    @Test
    fun finishingTwiceCompletesOnce() {
        val r = ask(CAMERA)
        val request = broker.head.value!!
        broker.finish(request, granted(CAMERA), blocked = false)
        broker.finish(request, denied(CAMERA), blocked = true)
        broker.evaluate(request, denied(CAMERA)) { false }
        assertEquals(listOf(true), r.values)
        assertFalse(broker.settingsHint.value)
    }

    @Test
    fun failedLaunchCompletesAsDeniedAndIsNeverBlocked() {
        val r = ask(CAMERA)
        val request = broker.head.value!!
        broker.failLaunch(request)
        broker.failLaunch(request)
        assertEquals(listOf(false), r.values)
        assertFalse(broker.settingsHint.value)
        assertNull(broker.head.value)
    }

    @Test
    fun aThrowingCallbackDoesNotStopTheOthersOrTheQueue() {
        broker.request(listOf(CAMERA)) { throw IllegalStateException("session closed") }
        val other = ask(CAMERA)
        val next = ask(RECORD_AUDIO)
        broker.finish(broker.head.value!!, granted(CAMERA), blocked = false)
        assertEquals(listOf(true), other.values)
        assertEquals(listOf(RECORD_AUDIO), broker.head.value!!.permissions)
        assertEquals(emptyList<Boolean>(), next.values)
    }

    @Test
    fun everyRequestCompletesExactlyOnceAcrossAMixedRun() {
        val all = mutableListOf<Results>()
        all += ask(CAMERA)
        all += ask(CAMERA)
        all += ask(RECORD_AUDIO)
        all += ask(FINE_LOCATION)
        all += ask(COARSE_LOCATION)
        all += ask("android.permission.READ_SMS")
        all += ask(CAMERA, RECORD_AUDIO)
        broker.finish(broker.head.value!!, denied(CAMERA), blocked = false)
        var guard = 0
        while (broker.head.value != null && guard++ < 20) {
            val head = broker.head.value!!
            broker.finish(head, head.permissions.associateWith { true }, blocked = false)
        }
        for (r in all) assertEquals(1, r.values.size)
    }

    // ---- blocked detection (post-result only) ----

    @Test
    fun deniedWithoutRationaleAfterBeingAskedBeforeIsBlocked() {
        asked.markAsked(CAMERA)
        val r = ask(CAMERA)
        broker.evaluate(broker.head.value!!, denied(CAMERA)) { false }
        assertEquals(listOf(false), r.values)
        assertTrue(broker.settingsHint.value)
    }

    @Test
    fun deniedWithRationaleIsNotBlocked() {
        asked.markAsked(CAMERA)
        ask(CAMERA)
        broker.evaluate(broker.head.value!!, denied(CAMERA)) { true }
        assertFalse(broker.settingsHint.value)
    }

    @Test
    fun firstEverDismissIsNotBlocked() {
        // Never asked before; a dismissed first dialog also reports "no rationale".
        ask(CAMERA)
        broker.evaluate(broker.head.value!!, denied(CAMERA)) { false }
        assertFalse(broker.settingsHint.value)
        assertTrue("but the dialog has now been shown", asked.wasAsked(CAMERA))
    }

    @Test
    fun theSecondDenialAfterTheFirstDismissIsBlocked() {
        ask(CAMERA)
        broker.evaluate(broker.head.value!!, denied(CAMERA)) { false }
        assertFalse(broker.settingsHint.value)
        clock += DENIAL_COOLDOWN_MS
        ask(CAMERA)
        broker.evaluate(broker.head.value!!, denied(CAMERA)) { false }
        assertTrue(broker.settingsHint.value)
    }

    @Test
    fun aGrantIsNeverBlockedEvenWithoutRationale() {
        asked.markAsked(CAMERA)
        val r = ask(CAMERA)
        broker.evaluate(broker.head.value!!, granted(CAMERA)) { false }
        assertEquals(listOf(true), r.values)
        assertFalse(broker.settingsHint.value)
    }

    @Test
    fun coarseGrantedWithFineDeniedIsNotBlocked() {
        asked.markAsked(FINE_LOCATION)
        asked.markAsked(COARSE_LOCATION)
        val r = ask(FINE_LOCATION)
        broker.evaluate(broker.head.value!!, mapOf(FINE_LOCATION to false, COARSE_LOCATION to true)) { false }
        assertEquals(listOf(true), r.values)
        assertFalse(broker.settingsHint.value)
    }

    @Test
    fun evaluateAlwaysLaunchesNeverPreChecks() {
        // Asked before and "no rationale" would look blocked, but a request is still queued for the system dialog.
        asked.markAsked(CAMERA)
        ask(CAMERA)
        assertNotNull(broker.head.value)
        assertFalse(broker.settingsHint.value)
    }

    @Test
    fun settingsHintCanBeDismissed() {
        asked.markAsked(CAMERA)
        ask(CAMERA)
        broker.evaluate(broker.head.value!!, denied(CAMERA)) { false }
        assertTrue(broker.settingsHint.value)
        broker.dismissSettingsHint()
        assertFalse(broker.settingsHint.value)
    }

    @Test
    fun blockedFlagGivenToFinishRaisesTheHintOnlyOnDenial() {
        ask(CAMERA)
        broker.finish(broker.head.value!!, granted(CAMERA), blocked = true)
        assertFalse(broker.settingsHint.value)
        ask(RECORD_AUDIO)
        broker.finish(broker.head.value!!, denied(RECORD_AUDIO), blocked = true)
        assertTrue(broker.settingsHint.value)
    }

    // ---- isSatisfied (used by the engine before it calls the broker) ----

    @Test
    fun isSatisfiedTreatsTheLocationPairAsEitherOr() {
        val onlyCoarse: (String) -> Boolean = { it == COARSE_LOCATION }
        assertTrue(AndroidPermissionBroker.isSatisfied(listOf(FINE_LOCATION), onlyCoarse))
        assertTrue(AndroidPermissionBroker.isSatisfied(listOf(FINE_LOCATION, COARSE_LOCATION), onlyCoarse))
        assertFalse(AndroidPermissionBroker.isSatisfied(listOf(FINE_LOCATION), { false }))
        assertFalse(AndroidPermissionBroker.isSatisfied(listOf(CAMERA, FINE_LOCATION), onlyCoarse))
        assertTrue(AndroidPermissionBroker.isSatisfied(listOf(CAMERA, FINE_LOCATION), { it != RECORD_AUDIO && it != FINE_LOCATION }))
    }

    @Test
    fun isSatisfiedNeedsEveryNonLocationPermission() {
        assertTrue(AndroidPermissionBroker.isSatisfied(listOf(CAMERA, RECORD_AUDIO), { true }))
        assertFalse(AndroidPermissionBroker.isSatisfied(listOf(CAMERA, RECORD_AUDIO), { it == CAMERA }))
        assertFalse(AndroidPermissionBroker.isSatisfied(emptyList(), { true }))
    }

    @Test
    fun headIsSameInstanceUntilItFinishes() {
        ask(CAMERA)
        val first = broker.head.value
        ask(CAMERA)
        assertSame(first, broker.head.value)
    }
}

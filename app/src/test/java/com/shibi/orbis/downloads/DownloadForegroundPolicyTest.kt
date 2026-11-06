package com.shibi.orbis.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadForegroundPolicyTest {
    private var clock = 10_000L
    private val policy = DownloadForegroundPolicy { clock }

    private fun model(vararg ids: Long, name: String? = "a.bin", percent: Int? = 0) =
        DownloadNotificationModel(ids.sortedArray(), ids.size, if (ids.size == 1) name else null, percent)

    private fun active(vararg ids: Long, percent: Int? = 0) = policy.onActive(ids.toSet(), model(*ids, percent = percent))

    private fun none() = policy.onActive(emptySet(), null)

    private fun assertStart(cmds: List<DownloadFgCommand>) {
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] === DownloadFgCommand.StartService)
    }

    private fun assertStop(cmds: List<DownloadFgCommand>) {
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] === DownloadFgCommand.StopService)
    }

    private fun assertUpdate(cmds: List<DownloadFgCommand>) {
        assertEquals(1, cmds.size)
        assertTrue(cmds[0] is DownloadFgCommand.UpdateNotification)
    }

    private fun toRunning(vararg ids: Long) {
        assertStart(active(*ids))
        assertTrue(policy.onServiceStarted().isEmpty())
        assertEquals(DownloadFgState.RUNNING, policy.state)
    }

    // ---- start ----

    @Test
    fun idleStaysIdleWithoutDownloads() {
        assertTrue(none().isEmpty())
        assertEquals(DownloadFgState.IDLE, policy.state)
    }

    @Test
    fun firstDownloadStartsTheService() {
        assertStart(active(1))
        assertEquals(DownloadFgState.STARTING, policy.state)
    }

    @Test
    fun moreDownloadsWhileStartingEmitNothing() {
        assertStart(active(1))
        assertTrue(active(1, 2).isEmpty())
        assertEquals(DownloadFgState.STARTING, policy.state)
    }

    @Test
    fun serviceStartedWithActiveIsRunning() {
        toRunning(1)
    }

    @Test
    fun emptyQueueStartStopsTheServiceAgain() {
        assertStart(active(1))
        none() // the queue emptied before onStartCommand ran
        assertStop(policy.onServiceStarted())
        assertEquals(DownloadFgState.STOPPING, policy.state)
    }

    // ---- stop ----

    @Test
    fun emptyWhileRunningStops() {
        toRunning(1)
        assertStop(none())
        assertEquals(DownloadFgState.STOPPING, policy.state)
    }

    @Test
    fun newDownloadWhileStoppingRestarts() {
        toRunning(1)
        none()
        assertStart(active(2))
        assertEquals(DownloadFgState.STARTING, policy.state)
    }

    @Test
    fun emptyWhileStoppingEmitsNothing() {
        toRunning(1)
        none()
        assertTrue(none().isEmpty())
        assertEquals(DownloadFgState.STOPPING, policy.state)
    }

    @Test
    fun destroyFromStoppingGoesIdle() {
        toRunning(1)
        none()
        assertTrue(policy.onServiceDestroyed().isEmpty())
        assertEquals(DownloadFgState.IDLE, policy.state)
    }

    @Test
    fun destroyDuringStartingStaysStarting() {
        toRunning(1)
        none()
        assertStart(active(2))
        policy.onServiceDestroyed() // old instance
        assertEquals(DownloadFgState.STARTING, policy.state)
        assertTrue(policy.onServiceStarted().isEmpty())
        assertEquals(DownloadFgState.RUNNING, policy.state)
    }

    @Test
    fun destroyFromRunningWithActiveRestarts() {
        toRunning(1)
        assertStart(policy.onServiceDestroyed())
        assertEquals(DownloadFgState.STARTING, policy.state)
    }

    // ---- start refused ----

    @Test
    fun startFailureBlocksWithoutRetry() {
        assertStart(active(1))
        assertTrue(policy.onStartFailed().isEmpty())
        assertEquals(DownloadFgState.START_BLOCKED, policy.state)
        assertTrue(active(1, percent = 50).isEmpty())
        assertTrue(active(1, percent = 60).isEmpty())
        assertEquals(DownloadFgState.START_BLOCKED, policy.state)
    }

    @Test
    fun newIdRetriesFromBlocked() {
        assertStart(active(1))
        policy.onStartFailed()
        assertStart(active(1, 2))
        assertEquals(DownloadFgState.STARTING, policy.state)
    }

    @Test
    fun foregroundRetriesFromBlockedWhenActive() {
        assertStart(active(1))
        policy.onStartFailed()
        assertStart(policy.onAppForeground())
        assertEquals(DownloadFgState.STARTING, policy.state)
    }

    @Test
    fun foregroundWithNothingActiveGoesIdle() {
        assertStart(active(1))
        policy.onStartFailed()
        none()
        assertEquals(DownloadFgState.START_BLOCKED, policy.state)
        assertTrue(policy.onAppForeground().isEmpty())
        assertEquals(DownloadFgState.IDLE, policy.state)
    }

    @Test
    fun destroyAfterFailedStartKeepsBlocked() {
        assertStart(active(1))
        policy.onStartFailed()
        assertTrue(policy.onServiceDestroyed().isEmpty())
        assertEquals(DownloadFgState.START_BLOCKED, policy.state)
    }

    // ---- timeout ----

    @Test
    fun timeoutStopsThenTimedOutOnceDestroyed() {
        toRunning(1)
        assertStop(policy.onTimeout())
        assertEquals(DownloadFgState.STOPPING, policy.state)
        assertTrue(policy.onServiceDestroyed().isEmpty())
        assertEquals(DownloadFgState.TIMED_OUT, policy.state)
    }

    @Test
    fun noRestartBetweenTimeoutAndDestroy() {
        toRunning(1)
        policy.onTimeout()
        assertTrue(active(1, percent = 40).isEmpty())
        assertTrue(active(1, 2).isEmpty())
        assertEquals(DownloadFgState.STOPPING, policy.state)
    }

    @Test
    fun timedOutDoesNotRestartUntilForeground() {
        toRunning(1)
        policy.onTimeout()
        policy.onServiceDestroyed()
        assertTrue(active(1, percent = 70).isEmpty())
        assertEquals(DownloadFgState.TIMED_OUT, policy.state)
        assertStart(policy.onAppForeground())
        assertEquals(DownloadFgState.STARTING, policy.state)
    }

    @Test
    fun timedOutWithNothingActiveGoesIdleOnForeground() {
        toRunning(1)
        policy.onTimeout()
        policy.onServiceDestroyed()
        none()
        assertTrue(policy.onAppForeground().isEmpty())
        assertEquals(DownloadFgState.IDLE, policy.state)
    }

    @Test
    fun foregroundWhileRunningIsIgnored() {
        toRunning(1)
        assertTrue(policy.onAppForeground().isEmpty())
        assertEquals(DownloadFgState.RUNNING, policy.state)
    }

    // ---- never update unless RUNNING ----

    @Test
    fun noUpdateInAnyStateButRunning() {
        // IDLE
        assertTrue(none().isEmpty())
        // STARTING
        assertStart(active(1))
        clock += 5000
        assertTrue(active(1, percent = 30).isEmpty())
        policy.onServiceStarted()
        // STOPPING
        none()
        clock += 5000
        assertEquals(DownloadFgState.STOPPING, policy.state)
        assertFalse(none().any { it is DownloadFgCommand.UpdateNotification })
        // START_BLOCKED
        assertStart(active(1))
        policy.onStartFailed()
        clock += 5000
        assertTrue(active(1, percent = 90).isEmpty())
        // TIMED_OUT
        assertStart(policy.onAppForeground())
        policy.onServiceStarted()
        policy.onTimeout()
        policy.onServiceDestroyed()
        clock += 5000
        assertEquals(DownloadFgState.TIMED_OUT, policy.state)
        assertTrue(active(1, percent = 95).isEmpty())
    }

    // ---- throttle ----

    @Test
    fun percentChangeWithinOneSecondIsHeldBack() {
        toRunning(1)
        clock += 400
        assertTrue(active(1, percent = 5).isEmpty())
        assertTrue(policy.needsTrailingUpdate())
    }

    @Test
    fun percentChangeAfterOneSecondPosts() {
        toRunning(1)
        clock += 1000
        assertUpdate(active(1, percent = 5))
        assertFalse(policy.needsTrailingUpdate())
    }

    @Test
    fun unchangedPercentNeverPosts() {
        toRunning(1)
        clock += 10_000
        assertTrue(active(1, percent = 0).isEmpty())
        assertFalse(policy.needsTrailingUpdate())
    }

    @Test
    fun atMostOncePerSecondAcrossManyEmissions() {
        toRunning(1)
        var posts = 0
        for (i in 1..40) {
            clock += 250
            posts += active(1, percent = i).count { it is DownloadFgCommand.UpdateNotification }
        }
        // 10 seconds of 250 ms ticks.
        assertTrue("posted $posts", posts in 1..10)
    }

    @Test
    fun structuralChangeIsImmediate() {
        toRunning(1)
        clock += 10
        assertUpdate(active(1, 2))
        clock += 10
        assertUpdate(policy.onActive(setOf(2L), model(2L)))
    }

    @Test
    fun nameChangeIsStructural() {
        toRunning(1)
        clock += 10
        assertUpdate(policy.onActive(setOf(1L), model(1L, name = "renamed.bin")))
    }

    @Test
    fun heldBackPercentPostsWhenFedAgainAfterTheInterval() {
        toRunning(1)
        clock += 300
        assertTrue(active(1, percent = 7).isEmpty())
        clock += DownloadForegroundPolicy.THROTTLE_MS
        assertUpdate(active(1, percent = 7))
    }

    @Test
    fun indeterminateToKnownPercentIsAPercentChange() {
        assertStart(policy.onActive(setOf(1L), model(1L, percent = null)))
        policy.onServiceStarted()
        clock += 1500
        assertUpdate(active(1, percent = 3))
    }
}

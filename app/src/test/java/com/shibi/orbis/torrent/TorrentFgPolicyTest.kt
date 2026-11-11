package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentFgPolicyTest {
    private var clock = 10_000L
    private val policy = TorrentFgPolicy { clock }

    private fun model(vararg ids: String, percent: Int? = 0, speed: Long = 0, name: String? = "t") =
        TorrentNotificationModel(ids.sorted(), if (ids.size == 1) name else null, false, percent, speed)

    private fun active(vararg ids: String, percent: Int? = 0, speed: Long = 0) = policy.onActive(ids.toSet(), model(*ids, percent = percent, speed = speed))

    private fun none() = policy.onActive(emptySet(), null)

    private fun toRunning(vararg ids: String) {
        assertEquals(listOf(TorrentFgCommand.StartService), active(*ids))
        assertTrue(policy.onServiceStarted().isEmpty())
        assertEquals(TorrentFgState.RUNNING, policy.state)
    }

    @Test
    fun startsForTheFirstActiveTorrentAndStopsAfterTheLast() {
        assertTrue(none().isEmpty())
        toRunning("a")
        assertEquals(listOf(TorrentFgCommand.StopService), none())
        assertEquals(TorrentFgState.STOPPING, policy.state)
        assertTrue(policy.onServiceDestroyed().isEmpty())
        assertEquals(TorrentFgState.IDLE, policy.state)
    }

    @Test
    fun aServiceThatComesUpWithNothingToDoLeavesAgain() {
        active("a")
        none()
        assertEquals(listOf(TorrentFgCommand.StopService), policy.onServiceStarted())
    }

    @Test
    fun progressAndSpeedAreThrottledStructureIsNot() {
        toRunning("a")
        assertTrue(active("a", percent = 1).isEmpty())
        assertTrue(active("a", percent = 1, speed = 500).isEmpty())
        clock += TorrentFgPolicy.THROTTLE_MS
        assertTrue(active("a", percent = 2, speed = 500).single() is TorrentFgCommand.UpdateNotification)
        assertTrue(active("a", percent = 3).isEmpty())
        assertTrue(policy.needsTrailingUpdate())
        // A second torrent changes the structure: posted at once.
        assertTrue(active("a", "b", percent = 3).single() is TorrentFgCommand.UpdateNotification)
        assertFalse(policy.needsTrailingUpdate())
    }

    @Test
    fun noUpdateIsPostedUnlessRunning() {
        assertEquals(listOf(TorrentFgCommand.StartService), active("a"))
        clock += 5_000
        assertTrue(active("a", percent = 50).isEmpty())
        assertFalse(policy.needsTrailingUpdate())
    }

    @Test
    fun timeoutStopsAndDoesNotRestartForTheSameTorrents() {
        toRunning("a")
        assertEquals(listOf(TorrentFgCommand.StopService), policy.onTimeout())
        assertTrue(policy.onServiceDestroyed().isEmpty())
        assertEquals(TorrentFgState.TIMED_OUT, policy.state)
        assertTrue(active("a", percent = 9).isEmpty())
        // The user resumes another torrent: a new id may start the service again.
        assertEquals(listOf(TorrentFgCommand.StartService), active("a", "b"))
    }

    @Test
    fun refusedStartIsRetriedWhenTheAppComesToTheForeground() {
        active("a")
        assertTrue(policy.onStartFailed().isEmpty())
        assertEquals(TorrentFgState.START_BLOCKED, policy.state)
        assertTrue(active("a", percent = 4).isEmpty())
        assertEquals(listOf(TorrentFgCommand.StartService), policy.onAppForeground())
    }

    @Test
    fun aDestroyedServiceWithWorkLeftIsStartedAgain() {
        toRunning("a")
        assertEquals(listOf(TorrentFgCommand.StartService), policy.onServiceDestroyed())
    }
}

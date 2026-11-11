package com.shibi.orbis.torrent

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

class FakeTorrentNetwork : TorrentNetwork {
    override val kind = MutableStateFlow(NetworkKind.WIFI_OR_ETHERNET)
}

/**
 * A [TorrentManager] on a [FakeTorrentEngine], files below [root], every coroutine on [scheduler] (advance it to let
 * the manager work). Shared by the manager tests and the Downloads screen tests. Cancel [scope] when done.
 */
class TorrentTestSetup(root: File, scheduler: TestCoroutineScheduler, freeBytes: Long = Long.MAX_VALUE / 4) {
    class Published(val content: String, val dir: String, val name: String, val handle: String)

    val engine = FakeTorrentEngine()
    val stateDir = File(root, "state")
    val payloadRoot = File(root, "payload")
    val store = TorrentStore(stateDir)
    val logs = CopyOnWriteArrayList<String>()
    val network = FakeTorrentNetwork()
    val settings = MutableStateFlow(TorrentSettings())
    val published = CopyOnWriteArrayList<Published>()
    val unpublished = CopyOnWriteArrayList<String>()
    var publishOk = true

    /** Fails the publish of the n-th file (1-based, counted over every attempt); 0 never. */
    var failPublishAt = 0
    private var publishCount = 0
    var clock = 1_000L

    // Not backgroundScope: advanceUntilIdle does not wait for background work.
    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    val manager =
        TorrentManager(
            engine = engine,
            store = store,
            dirs = TorrentDirs(payloadRoot = { payloadRoot }, freeBytes = { freeBytes }),
            scope = scope,
            io = StandardTestDispatcher(scheduler),
            log = { logs += it },
            settings = settings,
            network = network,
            now = { clock },
            publisher =
                object : TorrentPublisher {
                    override fun publish(file: File, relativeDir: String, displayName: String): String? {
                        publishCount++
                        if (!publishOk || publishCount == failPublishAt) return null
                        val handle = "content://media/external/downloads/$publishCount"
                        published += Published(file.readText(), relativeDir, displayName, handle)
                        return handle
                    }

                    override fun unpublish(handle: String) {
                        unpublished += handle
                    }
                },
        )

    fun only(): TorrentEntry = manager.entries.value.single()

    fun entry(id: String): TorrentEntry = manager.entries.value.first { it.id == id }

    fun lastId(): EngineId = engine.added.last().id
}

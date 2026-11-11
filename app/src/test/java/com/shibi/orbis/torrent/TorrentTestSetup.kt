package com.shibi.orbis.torrent

import com.shibi.orbis.downloads.PublishRow
import com.shibi.orbis.downloads.publishReuse
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

    val stateDir = File(root, "state")
    val payloadRoot = File(root, "payload")

    /** The private folder of the downloads directory, where finished private torrents are kept. */
    val keepRoot = File(root, "downloads/" + com.shibi.orbis.downloads.DownloadManager.PRIVATE_DIR)
    val store = TorrentStore(stateDir)
    val engine = FakeTorrentEngine(sessionFile = store.sessionFile)
    val logs = CopyOnWriteArrayList<String>()
    val network = FakeTorrentNetwork()
    val settings = MutableStateFlow(TorrentSettings())
    val published = CopyOnWriteArrayList<Published>()
    val unpublished = CopyOnWriteArrayList<String>()
    var publishOk = true

    /** Files (by display name) that already have a finished copy: published as reused, never copied again. */
    val existingCopies = HashSet<String>()

    /** Display names whose half-written (pending) copy the publisher deleted before copying again. */
    val pendingDeleted = CopyOnWriteArrayList<String>()

    /** Display names that have a pending (half-written) copy left over. */
    val pendingCopies = HashSet<String>()

    /** Fails the publish of the n-th file (1-based, counted over every attempt); 0 never. */
    var failPublishAt = 0

    /** Runs as each file's publish starts (the row is PUBLISHING, the copy not done). */
    var onPublish: () -> Unit = {}
    private var publishCount = 0
    var clock = 1_000L

    // Not backgroundScope: advanceUntilIdle does not wait for background work.
    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    val manager =
        TorrentManager(
            engine = engine,
            store = store,
            dirs = TorrentDirs(payloadRoot = { payloadRoot }, keepRoot = { keepRoot }, freeBytes = { freeBytes }),
            scope = scope,
            io = StandardTestDispatcher(scheduler),
            log = { logs += it },
            settings = settings,
            listenPort = { LISTEN_PORT },
            network = network,
            now = { clock },
            publisher =
                object : TorrentPublisher {
                    override fun publish(file: File, relativeDir: String, displayName: String): TorrentPublisher.Published? {
                        onPublish()
                        publishCount++
                        if (!publishOk || publishCount == failPublishAt) return null
                        val handle = "content://media/external/downloads/$publishCount"
                        // The same decision the MediaStore publisher makes (publishReuse).
                        val rows =
                            listOfNotNull(
                                PublishRow(publishCount * 1000L, file.length(), pending = false).takeIf { displayName in existingCopies },
                                PublishRow(publishCount * 1000L + 1, 1L, pending = true).takeIf { displayName in pendingCopies },
                            )
                        val decision = publishReuse(rows, file.length())
                        if (decision.deletePending.isNotEmpty()) pendingDeleted += displayName
                        if (decision.reuse != null) return TorrentPublisher.Published("content://media/external/downloads/existing/${decision.reuse}", reused = true)
                        published += Published(file.readText(), relativeDir, displayName, handle)
                        return TorrentPublisher.Published(handle, reused = false)
                    }

                    override fun unpublish(handle: String) {
                        unpublished += handle
                    }
                },
        )

    fun only(): TorrentEntry = manager.entries.value.single()

    fun entry(id: String): TorrentEntry = manager.entries.value.first { it.id == id }

    fun lastId(): EngineId = engine.added.last().id

    companion object {
        const val LISTEN_PORT = 50123
    }
}

package com.shibi.orbis.torrent

enum class TorrentFgState { IDLE, STARTING, RUNNING, STOPPING, TIMED_OUT, START_BLOCKED }

sealed interface TorrentFgCommand {
    /** Ask Android to start the foreground service. The service must call startForeground first, always. */
    object StartService : TorrentFgCommand

    /** Tell the running service to stop itself. Nobody outside the service ever calls stopService. */
    object StopService : TorrentFgCommand

    /** Repost the ongoing notification. Only ever emitted while RUNNING. */
    class UpdateNotification(val model: TorrentNotificationModel) : TorrentFgCommand
}

/**
 * Decides when the torrent foreground service runs; the torrent twin of downloads/DownloadForegroundPolicy. Main
 * thread only, no Android types: the controller feeds it events and executes the commands it returns.
 *
 * A post after the service stopped would leave an orphan notification, so UpdateNotification is emitted only in
 * RUNNING. Progress and speed are throttled to one post per [THROTTLE_MS]; a change of structure (ids, name, private)
 * is posted at once. After an Android 15 timeout the torrents are paused by the manager, so no restart follows it
 * until the user resumes one (a new active id).
 */
class TorrentFgPolicy(private val now: () -> Long) {
    var state: TorrentFgState = TorrentFgState.IDLE
        private set

    private var active: Set<String> = emptySet()
    private var latest: TorrentNotificationModel? = null
    private var posted: TorrentNotificationModel? = null
    private var postedAt = 0L
    private var timeoutPending = false

    fun onActive(ids: Set<String>, model: TorrentNotificationModel?): List<TorrentFgCommand> {
        val hasNewId = ids.any { it !in active }
        active = ids
        latest = if (ids.isEmpty()) null else model
        return when (state) {
            TorrentFgState.IDLE -> if (ids.isNotEmpty()) startNow() else NONE
            TorrentFgState.STARTING -> NONE
            TorrentFgState.RUNNING ->
                if (ids.isEmpty()) {
                    state = TorrentFgState.STOPPING
                    listOf(TorrentFgCommand.StopService)
                } else {
                    maybeUpdate()
                }
            TorrentFgState.STOPPING -> if (ids.isNotEmpty() && !timeoutPending) startNow() else NONE
            TorrentFgState.TIMED_OUT, TorrentFgState.START_BLOCKED -> if (hasNewId) startNow() else NONE
        }
    }

    fun onServiceStarted(): List<TorrentFgCommand> {
        state = TorrentFgState.RUNNING
        timeoutPending = false
        posted = latest
        postedAt = now()
        if (active.isNotEmpty()) return NONE
        state = TorrentFgState.STOPPING
        return listOf(TorrentFgCommand.StopService)
    }

    fun onServiceDestroyed(): List<TorrentFgCommand> =
        when (state) {
            TorrentFgState.STOPPING ->
                if (timeoutPending) {
                    timeoutPending = false
                    state = TorrentFgState.TIMED_OUT
                    NONE
                } else {
                    state = TorrentFgState.IDLE
                    if (active.isNotEmpty()) startNow() else NONE
                }
            TorrentFgState.RUNNING -> {
                state = TorrentFgState.IDLE
                if (active.isNotEmpty()) startNow() else NONE
            }
            else -> NONE
        }

    fun onStartFailed(): List<TorrentFgCommand> {
        state = TorrentFgState.START_BLOCKED
        timeoutPending = false
        return NONE
    }

    /** Android 15+ dataSync budget ran out: the service stops itself; the manager pauses the torrents. */
    fun onTimeout(): List<TorrentFgCommand> {
        timeoutPending = true
        state = TorrentFgState.STOPPING
        return listOf(TorrentFgCommand.StopService)
    }

    fun onAppForeground(): List<TorrentFgCommand> =
        when (state) {
            TorrentFgState.TIMED_OUT, TorrentFgState.START_BLOCKED ->
                if (active.isNotEmpty()) {
                    startNow()
                } else {
                    state = TorrentFgState.IDLE
                    NONE
                }
            else -> NONE
        }

    /** True when RUNNING and the shown notification is behind the latest model. */
    fun needsTrailingUpdate(): Boolean {
        val l = latest ?: return false
        val p = posted ?: return state == TorrentFgState.RUNNING
        return state == TorrentFgState.RUNNING && l != p
    }

    private fun startNow(): List<TorrentFgCommand> {
        state = TorrentFgState.STARTING
        timeoutPending = false
        return listOf(TorrentFgCommand.StartService)
    }

    private fun maybeUpdate(): List<TorrentFgCommand> {
        val m = latest ?: return NONE
        val p = posted
        val t = now()
        val allowed = p == null || !m.sameStructure(p) || (!m.sameNumbers(p) && t - postedAt >= THROTTLE_MS)
        if (!allowed) return NONE
        posted = m
        postedAt = t
        return listOf(TorrentFgCommand.UpdateNotification(m))
    }

    companion object {
        const val THROTTLE_MS = 1000L
        private val NONE: List<TorrentFgCommand> = emptyList()
    }
}

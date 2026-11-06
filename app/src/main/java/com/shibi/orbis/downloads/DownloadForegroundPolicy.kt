package com.shibi.orbis.downloads

enum class DownloadFgState { IDLE, STARTING, RUNNING, STOPPING, TIMED_OUT, START_BLOCKED }

sealed interface DownloadFgCommand {
    /** Ask Android to start the foreground service. The service must call startForeground first, always. */
    object StartService : DownloadFgCommand

    /** Tell the running service to stop itself. Nobody outside the service ever calls stopService. */
    object StopService : DownloadFgCommand

    /** Repost the ongoing notification. Only ever emitted while RUNNING. */
    class UpdateNotification(val model: DownloadNotificationModel) : DownloadFgCommand
}

/**
 * Decides when the download foreground service runs. Main thread only, no Android types: the controller feeds it
 * events and executes the commands it returns.
 *
 * A post after the service stopped would leave an orphan ongoing notification, so UpdateNotification is emitted
 * only in RUNNING. Android silently drops notification bursts above ~5/s, final updates included, so progress is
 * throttled to one post per [THROTTLE_MS] and only when the percent changed; a change of structure (ids, count,
 * name) is posted at once.
 */
class DownloadForegroundPolicy(private val now: () -> Long) {
    var state: DownloadFgState = DownloadFgState.IDLE
        private set

    private var active: Set<Long> = emptySet()
    private var latest: DownloadNotificationModel? = null
    private var posted: DownloadNotificationModel? = null
    private var postedAt = 0L

    /** The service was told to stop because Android 15 timed it out: the next destroy leads to TIMED_OUT, not a restart. */
    private var timeoutPending = false

    fun onActive(ids: Set<Long>, model: DownloadNotificationModel?): List<DownloadFgCommand> {
        val hasNewId = ids.any { it !in active }
        active = ids
        latest = if (ids.isEmpty()) null else model
        return when (state) {
            DownloadFgState.IDLE -> if (ids.isNotEmpty()) startNow() else NONE
            DownloadFgState.STARTING -> NONE
            DownloadFgState.RUNNING ->
                if (ids.isEmpty()) {
                    state = DownloadFgState.STOPPING
                    listOf(DownloadFgCommand.StopService)
                } else {
                    maybeUpdate()
                }
            DownloadFgState.STOPPING -> if (ids.isNotEmpty() && !timeoutPending) startNow() else NONE
            DownloadFgState.TIMED_OUT, DownloadFgState.START_BLOCKED -> if (hasNewId) startNow() else NONE
        }
    }

    /** The service ran onStartCommand and called startForeground. */
    fun onServiceStarted(): List<DownloadFgCommand> {
        state = DownloadFgState.RUNNING
        timeoutPending = false
        posted = latest
        postedAt = now()
        if (active.isNotEmpty()) return NONE
        // The queue emptied before the service came up: it still had to enter the foreground, now it leaves again.
        state = DownloadFgState.STOPPING
        return listOf(DownloadFgCommand.StopService)
    }

    fun onServiceDestroyed(): List<DownloadFgCommand> =
        when (state) {
            DownloadFgState.STOPPING ->
                if (timeoutPending) {
                    timeoutPending = false
                    state = DownloadFgState.TIMED_OUT
                    NONE
                } else {
                    state = DownloadFgState.IDLE
                    if (active.isNotEmpty()) startNow() else NONE
                }
            DownloadFgState.RUNNING -> {
                state = DownloadFgState.IDLE
                if (active.isNotEmpty()) startNow() else NONE
            }
            // STARTING: an old instance's destroy can be ordered before the new instance's create.
            else -> NONE
        }

    /** Android refused the foreground start (or the service could not enter the foreground). No retry loop. */
    fun onStartFailed(): List<DownloadFgCommand> {
        state = DownloadFgState.START_BLOCKED
        timeoutPending = false
        return NONE
    }

    /** Android 15 dataSync quota (6h) ran out. The downloads keep running, unprotected. */
    fun onTimeout(): List<DownloadFgCommand> {
        timeoutPending = true
        state = DownloadFgState.STOPPING
        return listOf(DownloadFgCommand.StopService)
    }

    fun onAppForeground(): List<DownloadFgCommand> =
        when (state) {
            DownloadFgState.TIMED_OUT, DownloadFgState.START_BLOCKED ->
                if (active.isNotEmpty()) {
                    startNow()
                } else {
                    state = DownloadFgState.IDLE
                    NONE
                }
            else -> NONE
        }

    /** True when RUNNING and the shown notification is behind the latest model: the caller should re-feed onActive later. */
    fun needsTrailingUpdate(): Boolean {
        val l = latest ?: return false
        val p = posted ?: return state == DownloadFgState.RUNNING
        return state == DownloadFgState.RUNNING && (l.percent != p.percent || !l.sameStructure(p))
    }

    private fun startNow(): List<DownloadFgCommand> {
        state = DownloadFgState.STARTING
        timeoutPending = false
        return listOf(DownloadFgCommand.StartService)
    }

    private fun maybeUpdate(): List<DownloadFgCommand> {
        val m = latest ?: return NONE
        val p = posted
        val t = now()
        val allowed = p == null || !m.sameStructure(p) || (m.percent != p.percent && t - postedAt >= THROTTLE_MS)
        if (!allowed) return NONE
        posted = m
        postedAt = t
        return listOf(DownloadFgCommand.UpdateNotification(m))
    }

    companion object {
        const val THROTTLE_MS = 1000L
        private val NONE: List<DownloadFgCommand> = emptyList()
    }
}

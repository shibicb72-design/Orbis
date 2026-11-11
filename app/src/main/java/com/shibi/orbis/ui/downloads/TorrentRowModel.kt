package com.shibi.orbis.ui.downloads

import com.shibi.orbis.torrent.PauseReason
import com.shibi.orbis.torrent.TorrentEntry
import com.shibi.orbis.torrent.TorrentErrorKind
import com.shibi.orbis.torrent.TorrentStatus

/** What a torrent row says about its state (one line under the name). */
sealed interface RowState {
    data object Fetching : RowState

    data object Downloading : RowState

    data object Checking : RowState

    /** Finished, sharing with others until [limit] (null: until the user stops it). */
    data class Seeding(val ratio: Float, val limit: Float?) : RowState

    data object PausedUser : RowState

    data object PausedQueued : RowState

    data object PausedTimeout : RowState

    data object WaitingWifi : RowState

    data object Publishing : RowState

    data object Completed : RowState

    data class Failed(val kind: TorrentErrorKind) : RowState
}

enum class RowAction { PAUSE, RESUME, RETRY, OPEN, STOP_SEEDING, REMOVE }

/**
 * What one torrent row shows, from its [TorrentEntry]. Pure (no Android), so every state is unit tested.
 * A private torrent has no [title]: the row says "Private download" and never shows its name.
 */
class TorrentRowModel(
    val id: String,
    /** Null for a private torrent. */
    val title: String?,
    val isPrivate: Boolean,
    val doneBytes: Long,
    /** -1 while unknown. */
    val totalBytes: Long,
    /** Null while the total is unknown or the work has no measurable progress (checking, saving). */
    val percent: Int?,
    /** Download speed; upload speed while seeding. 0 when not running. */
    val speedBps: Long,
    /** Null when the torrent is not connected to anyone (not running). */
    val peers: Int?,
    val seeds: Int?,
    val etaSeconds: Long?,
    val state: RowState,
    val actions: Set<RowAction>,
) {
    /** The progress bar is shown without a value: the work goes on but its share is unknown. */
    val indeterminate: Boolean get() = percent == null && (state == RowState.Fetching || state == RowState.Checking || state == RowState.Publishing)

    // Deliberately no name: a torrent name must not end up in a log line.
    override fun toString(): String = "TorrentRowModel(state=$state, actions=$actions, private=$isPrivate)"

    companion object {
        private val RETRYABLE = setOf(TorrentErrorKind.NO_SPACE, TorrentErrorKind.FILE_ERROR, TorrentErrorKind.ENGINE_ERROR, TorrentErrorKind.PUBLISH_FAILED)

        /**
         * Null for a REMOVED entry (never listed). [seedRatioLimit]: the "Stop at ratio" setting, which applies only
         * while "Stop seeding when finished" is off (pass null then).
         */
        fun from(e: TorrentEntry, seedRatioLimit: Float? = null): TorrentRowModel? {
            val state = stateOf(e, seedRatioLimit) ?: return null
            val running = state == RowState.Fetching || state == RowState.Downloading || state is RowState.Seeding || state == RowState.Checking
            val percent =
                when {
                    state == RowState.Checking || state == RowState.Publishing || state == RowState.Fetching -> null
                    state == RowState.Completed -> 100
                    e.totalBytes <= 0 -> null
                    else -> ((e.doneBytes.coerceIn(0L, e.totalBytes).toDouble() * 100.0) / e.totalBytes.toDouble()).toInt().coerceIn(0, 100)
                }
            return TorrentRowModel(
                id = e.id,
                title = if (e.isPrivate) null else e.displayName,
                isPrivate = e.isPrivate,
                doneBytes = e.doneBytes.coerceAtLeast(0L),
                totalBytes = if (e.totalBytes > 0) e.totalBytes else -1L,
                percent = percent,
                speedBps = if (!running) 0L else if (state is RowState.Seeding) e.upBps.coerceAtLeast(0L) else e.downBps.coerceAtLeast(0L),
                peers = if (running && state != RowState.Checking) e.peers.coerceAtLeast(0) else null,
                seeds = if (running && state != RowState.Checking) e.seeds.coerceAtLeast(0) else null,
                etaSeconds = if (state == RowState.Downloading) e.etaSeconds?.takeIf { it >= 0 } else null,
                state = state,
                actions = actionsOf(state),
            )
        }

        private fun stateOf(e: TorrentEntry, seedRatioLimit: Float?): RowState? =
            when (e.status) {
                TorrentStatus.FETCHING_METADATA -> RowState.Fetching
                TorrentStatus.DOWNLOADING -> RowState.Downloading
                TorrentStatus.CHECKING -> RowState.Checking
                TorrentStatus.SEEDING -> RowState.Seeding(ratio = e.ratio.takeIf { it.isFinite() && it >= 0f } ?: 0f, limit = seedRatioLimit)
                TorrentStatus.PAUSED ->
                    when (e.pauseReason) {
                        PauseReason.QUEUED -> RowState.PausedQueued
                        PauseReason.TIMEOUT -> RowState.PausedTimeout
                        PauseReason.USER, null -> RowState.PausedUser
                    }
                TorrentStatus.WAITING_FOR_WIFI -> RowState.WaitingWifi
                TorrentStatus.PUBLISHING -> RowState.Publishing
                TorrentStatus.COMPLETED -> RowState.Completed
                TorrentStatus.FAILED -> RowState.Failed(e.errorKind ?: TorrentErrorKind.ENGINE_ERROR)
                TorrentStatus.REMOVED -> null
            }

        private fun actionsOf(state: RowState): Set<RowAction> =
            when (state) {
                RowState.Fetching, RowState.Downloading, RowState.Checking, RowState.WaitingWifi -> setOf(RowAction.PAUSE, RowAction.REMOVE)
                is RowState.Seeding -> setOf(RowAction.PAUSE, RowAction.STOP_SEEDING, RowAction.REMOVE)
                RowState.PausedUser, RowState.PausedQueued, RowState.PausedTimeout -> setOf(RowAction.RESUME, RowAction.REMOVE)
                // A copy in flight must finish first.
                RowState.Publishing -> emptySet()
                RowState.Completed -> setOf(RowAction.OPEN, RowAction.REMOVE)
                is RowState.Failed -> if (state.kind in RETRYABLE) setOf(RowAction.RETRY, RowAction.REMOVE) else setOf(RowAction.REMOVE)
            }

        /** "ratio 1.25": two decimals, like other torrent apps, so a slow rise is visible. */
        fun ratioText(ratio: Float): String = String.format(java.util.Locale.ROOT, "%.2f", ratio)

        /** The ratio limit with its one decimal: "1.0". */
        fun limitText(limit: Float): String = String.format(java.util.Locale.ROOT, "%.1f", limit)
    }
}

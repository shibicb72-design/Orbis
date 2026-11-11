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

    data object Seeding : RowState

    data object PausedUser : RowState

    data object PausedQueued : RowState

    data object PausedTimeout : RowState

    data object WaitingWifi : RowState

    data object Publishing : RowState

    data object Completed : RowState

    data class Failed(val kind: TorrentErrorKind) : RowState
}

enum class RowAction { PAUSE, RESUME, RETRY, OPEN, REMOVE }

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

        /** Null for a REMOVED entry (never listed). */
        fun from(e: TorrentEntry): TorrentRowModel? {
            val state = stateOf(e) ?: return null
            val running = state == RowState.Fetching || state == RowState.Downloading || state == RowState.Seeding || state == RowState.Checking
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
                speedBps = if (!running) 0L else if (state == RowState.Seeding) e.upBps.coerceAtLeast(0L) else e.downBps.coerceAtLeast(0L),
                peers = if (running && state != RowState.Checking) e.peers.coerceAtLeast(0) else null,
                seeds = if (running && state != RowState.Checking) e.seeds.coerceAtLeast(0) else null,
                etaSeconds = if (state == RowState.Downloading) e.etaSeconds?.takeIf { it >= 0 } else null,
                state = state,
                actions = actionsOf(state),
            )
        }

        private fun stateOf(e: TorrentEntry): RowState? =
            when (e.status) {
                TorrentStatus.FETCHING_METADATA -> RowState.Fetching
                TorrentStatus.DOWNLOADING -> RowState.Downloading
                TorrentStatus.CHECKING -> RowState.Checking
                TorrentStatus.SEEDING -> RowState.Seeding
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
                RowState.Fetching, RowState.Downloading, RowState.Checking, RowState.Seeding, RowState.WaitingWifi -> setOf(RowAction.PAUSE, RowAction.REMOVE)
                RowState.PausedUser, RowState.PausedQueued, RowState.PausedTimeout -> setOf(RowAction.RESUME, RowAction.REMOVE)
                // A copy in flight must finish first.
                RowState.Publishing -> emptySet()
                RowState.Completed -> setOf(RowAction.OPEN, RowAction.REMOVE)
                is RowState.Failed -> if (state.kind in RETRYABLE) setOf(RowAction.RETRY, RowAction.REMOVE) else setOf(RowAction.REMOVE)
            }
    }
}

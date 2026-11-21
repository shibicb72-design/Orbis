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

    /** A finished private torrent kept inside Orbis, not in Downloads (it can still be saved there). */
    data object Kept : RowState

    data class Failed(val kind: TorrentErrorKind) : RowState
}

/** What the engine knows about the search for peers (tor10-4). Memory only; [connected] is the row's own peer count. */
data class SearchFacts(
    val found: Int = 0,
    val connected: Int = 0,
    val trackersFailed: Int = 0,
    val trackersTotal: Int = 0,
    val dhtInUse: Boolean = true,
    /** Seconds run without a connected peer, counted up to [SEARCH_GIVE_UP_SECONDS]. */
    val seconds: Int = 0,
)

/** The second line of a row while it looks for peers, or the note under "Waiting for Wi-Fi". Words live in strings_torrent_status.xml. */
data class SearchLine(val kind: Kind, val found: Int = 0, val connected: Int = 0, val trackersAllFailed: Int = 0) {
    enum class Kind { LOOKING, FOUND_CONNECTING, FOUND_NONE_CONNECTED, CONNECTED, NO_PEERS, NO_PEERS_TRACKERS_ONLY, WIFI_ONLY_NOTE }
}

/** After this many seconds with no peer known (or none connected) the row says so. */
const val SEARCH_GIVE_UP_SECONDS = 60

/**
 * The search line for [model] (time comes in [SearchFacts.seconds], from the engine's own clock), or null when the row
 * says something else (speed, checking, paused, finished). Always non-null while a torrent fetches its details or
 * downloads without a speed yet, so the row does not change height.
 */
fun searchLine(model: TorrentRowModel): SearchLine? {
    if (model.state == RowState.WaitingWifi) return SearchLine(SearchLine.Kind.WIFI_ONLY_NOTE)
    val searching = model.state == RowState.Fetching || (model.state == RowState.Downloading && model.speedBps <= 0)
    if (!searching) return null
    val f = model.search
    val connected = f.connected.coerceAtLeast(0)
    val found = maxOf(f.found, connected)
    return when {
        connected > 0 -> SearchLine(SearchLine.Kind.CONNECTED, found = found, connected = connected)
        found > 0 && f.seconds >= SEARCH_GIVE_UP_SECONDS -> SearchLine(SearchLine.Kind.FOUND_NONE_CONNECTED, found = found)
        found > 0 -> SearchLine(SearchLine.Kind.FOUND_CONNECTING, found = found)
        else -> {
            val kind =
                when {
                    f.seconds < SEARCH_GIVE_UP_SECONDS -> SearchLine.Kind.LOOKING
                    f.dhtInUse -> SearchLine.Kind.NO_PEERS
                    else -> SearchLine.Kind.NO_PEERS_TRACKERS_ONLY
                }
            // Every tracker failed and the DHT is no help: say it, so "stuck" is not a guess.
            val allFailed = if (!f.dhtInUse && f.trackersTotal > 0 && f.trackersFailed >= f.trackersTotal) f.trackersTotal else 0
            SearchLine(kind, trackersAllFailed = allFailed)
        }
    }
}

enum class RowAction { PAUSE, RESUME, RETRY, OPEN, STOP_SEEDING, SAVE_TO_DOWNLOADS, REMOVE }

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
    val search: SearchFacts = SearchFacts(),
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
                    state == RowState.Completed || state == RowState.Kept -> 100
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
                search =
                    SearchFacts(
                        found = e.peersFound.coerceAtLeast(0),
                        connected = if (running && state != RowState.Checking) e.peers.coerceAtLeast(0) else 0,
                        trackersFailed = e.trackersFailed,
                        trackersTotal = e.trackersTotal,
                        dhtInUse = e.dhtInUse,
                        seconds = e.searchSeconds,
                    ),
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
                TorrentStatus.COMPLETED -> if (e.isPrivate && e.keptDir != null) RowState.Kept else RowState.Completed
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
                RowState.Kept -> setOf(RowAction.SAVE_TO_DOWNLOADS, RowAction.REMOVE)
                is RowState.Failed -> if (state.kind in RETRYABLE) setOf(RowAction.RETRY, RowAction.REMOVE) else setOf(RowAction.REMOVE)
            }

        /** "ratio 1.25": two decimals, like other torrent apps, so a slow rise is visible. */
        fun ratioText(ratio: Float): String = String.format(java.util.Locale.ROOT, "%.2f", ratio)

        /** The ratio limit with its one decimal: "1.0". */
        fun limitText(limit: Float): String = String.format(java.util.Locale.ROOT, "%.1f", limit)
    }
}

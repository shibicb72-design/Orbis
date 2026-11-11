package com.shibi.orbis.torrent

/**
 * What the one ongoing torrent notification shows. Android-free so it can be unit tested.
 *
 * [name] is set only for a single torrent that is not private. As soon as any shown torrent is private, [isPrivate]
 * is set and no name is shown at all: the shade must never name a private download.
 */
class TorrentNotificationModel(
    /** Every active torrent id, ascending. Pause and Cancel act on exactly these. */
    val ids: List<String>,
    val name: String?,
    val isPrivate: Boolean,
    /** Null while some total is unknown (metadata still coming). */
    val percent: Int?,
    val downBps: Long,
) {
    val count: Int get() = ids.size

    val isSingle: Boolean get() = ids.size == 1

    /** Same torrents and same title: posted at once when it changes; progress and speed are throttled. */
    fun sameStructure(other: TorrentNotificationModel): Boolean = ids == other.ids && name == other.name && isPrivate == other.isPrivate

    fun sameNumbers(other: TorrentNotificationModel): Boolean = percent == other.percent && downBps == other.downBps

    override fun equals(other: Any?): Boolean = other is TorrentNotificationModel && sameStructure(other) && sameNumbers(other)

    override fun hashCode(): Int = 31 * (31 * ids.hashCode() + (percent ?: -1)) + downBps.hashCode()

    // Deliberately no name: a torrent name must not end up in a log line.
    override fun toString(): String = "TorrentNotificationModel(count=$count, percent=$percent, private=$isPrivate)"

    companion object {
        /** Null when nothing is active (see [TorrentStatus.isActive]). */
        fun from(entries: List<TorrentEntry>): TorrentNotificationModel? {
            val active = entries.filter { it.status.isActive }
            if (active.isEmpty()) return null
            val anyPrivate = active.any { it.isPrivate }
            val name = if (active.size == 1 && !anyPrivate) active[0].displayName else null
            val known = active.all { it.totalBytes > 0 }
            val percent = if (known) percentOf(active.sumOf { it.doneBytes.coerceAtLeast(0L) }, active.sumOf { it.totalBytes }) else null
            return TorrentNotificationModel(active.map { it.id }.sorted(), name, anyPrivate, percent, active.sumOf { it.downBps.coerceAtLeast(0L) })
        }

        private fun percentOf(done: Long, total: Long): Int? {
            if (total <= 0) return null
            val d = done.coerceIn(0L, total)
            return ((d.toDouble() * 100.0) / total.toDouble()).toInt().coerceIn(0, 100)
        }
    }
}

/**
 * What the "paused" notification shows (only after a pause from the notification or Android's timeout; a pause from
 * the list posts nothing). Android-free. A private torrent among them: no name anywhere, a generic title.
 */
class TorrentPausedModel(
    /** The paused torrents, ascending. */
    val ids: List<String>,
    val isPrivate: Boolean,
    /** Android ended the time budget (the text tells the user to tap Resume); false for a pause from the notification. */
    val byTimeout: Boolean,
) {
    val count: Int get() = ids.size

    /** Resume acts on this one id; null resumes every PAUSED(USER|TIMEOUT) torrent. */
    val resumeId: String? get() = ids.singleOrNull()

    override fun equals(other: Any?): Boolean = other is TorrentPausedModel && ids == other.ids && isPrivate == other.isPrivate && byTimeout == other.byTimeout

    override fun hashCode(): Int = 31 * (31 * ids.hashCode() + isPrivate.hashCode()) + byTimeout.hashCode()

    override fun toString(): String = "TorrentPausedModel(count=$count, private=$isPrivate, timeout=$byTimeout)"

    companion object {
        /**
         * Null when none of [pausedIds] is still PAUSED by the user or the timeout (resumed, removed, queued, or gone
         * with private browsing): then the notification is cancelled.
         */
        fun from(entries: List<TorrentEntry>, pausedIds: Set<String>, byTimeout: Boolean): TorrentPausedModel? {
            val paused =
                entries.filter {
                    it.id in pausedIds && it.status == TorrentStatus.PAUSED && (it.pauseReason == PauseReason.USER || it.pauseReason == PauseReason.TIMEOUT)
                }
            if (paused.isEmpty()) return null
            return TorrentPausedModel(paused.map { it.id }.sorted(), paused.any { it.isPrivate }, byTimeout)
        }
    }
}

/** Which title a torrent's result notification gets. A private torrent's never names it. */
enum class TorrentResultTitle { DONE, FAILED, PRIVATE_DONE, PRIVATE_FAILED }

/** Title and text of the result notification for one torrent; [text] (the name) is null for a private one. */
class TorrentResultContent(val title: TorrentResultTitle, val text: String?) {
    // Deliberately no text: a torrent name must not end up in a log line.
    override fun toString(): String = "TorrentResultContent(title=$title)"

    companion object {
        fun from(entry: TorrentEntry): TorrentResultContent {
            val done = entry.status == TorrentStatus.COMPLETED
            if (entry.isPrivate) return TorrentResultContent(if (done) TorrentResultTitle.PRIVATE_DONE else TorrentResultTitle.PRIVATE_FAILED, null)
            return TorrentResultContent(if (done) TorrentResultTitle.DONE else TorrentResultTitle.FAILED, entry.displayName)
        }
    }
}

/**
 * Finds torrents that settled (COMPLETED or FAILED) since the previous snapshot, each once per settling. Only an id
 * that was in the previous snapshot and not settled there can be reported: the list as restored at process start, and
 * an entry that appears already failed (a refused link), report nothing.
 */
class TorrentSettledTracker {
    private var previous: Map<String, TorrentStatus>? = null

    fun update(entries: List<TorrentEntry>): List<TorrentEntry> {
        val before = previous
        previous = entries.associate { it.id to it.status }
        if (before == null) return emptyList()
        return entries.filter {
            val was = before[it.id]
            it.status.isSettled && was != null && !was.isSettled
        }
    }

    private val TorrentStatus.isSettled: Boolean get() = this == TorrentStatus.COMPLETED || this == TorrentStatus.FAILED
}

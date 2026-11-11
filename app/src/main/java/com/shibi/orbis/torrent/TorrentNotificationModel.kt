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

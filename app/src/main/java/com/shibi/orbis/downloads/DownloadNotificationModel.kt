package com.shibi.orbis.downloads

/**
 * What the ongoing download notification shows. Android-free so it can be unit tested.
 *
 * [percent] is null when the progress is indeterminate (some total unknown). [name] is set only for a single download:
 * with several, individual names would make the notification restructure on every file-name change. A private
 * download never has a name here ([isPrivate] is set instead): the shade must not show a private file name.
 */
class DownloadNotificationModel(
    /** Every active download, ascending. The Cancel action carries exactly these. */
    val ids: LongArray,
    val count: Int,
    val name: String?,
    val percent: Int?,
    /** A single download from a private tab: the title is a generic "Private download". */
    val isPrivate: Boolean = false,
) {
    val isSingle: Boolean get() = count == 1

    /** Same downloads and same title: a change in structure is posted at once, a change in percent is throttled. */
    fun sameStructure(other: DownloadNotificationModel): Boolean =
        count == other.count && name == other.name && isPrivate == other.isPrivate && ids.contentEquals(other.ids)

    override fun equals(other: Any?): Boolean =
        other is DownloadNotificationModel && sameStructure(other) && percent == other.percent

    override fun hashCode(): Int = 31 * (31 * ids.contentHashCode() + count) + (percent ?: -1)

    // Deliberately no name: a file name must not end up in a log line.
    override fun toString(): String = "DownloadNotificationModel(count=$count, percent=$percent)"

    companion object {
        /** Null when nothing is downloading. Only IN_PROGRESS entries count. */
        fun from(entries: List<DownloadEntry>): DownloadNotificationModel? {
            val active = entries.filter { it.status == DownloadStatus.IN_PROGRESS }
            if (active.isEmpty()) return null
            val ids = active.map { it.id }.sorted().toLongArray()
            if (active.size == 1) {
                val e = active[0]
                val name = if (e.isPrivate) null else e.fileName
                return DownloadNotificationModel(ids, 1, name, percentOf(e.receivedBytes, e.totalBytes), isPrivate = e.isPrivate)
            }
            val allKnown = active.all { it.totalBytes > 0 }
            val percent =
                if (allKnown) percentOf(active.sumOf { it.receivedBytes.coerceAtLeast(0L) }, active.sumOf { it.totalBytes }) else null
            return DownloadNotificationModel(ids, active.size, null, percent)
        }

        /** Null for an unknown total (<= 0). Clamped to 0..100 so a body longer than announced never shows 140%. */
        private fun percentOf(received: Long, total: Long): Int? {
            if (total <= 0) return null
            val r = received.coerceIn(0L, total)
            return ((r.toDouble() * 100.0) / total.toDouble()).toInt().coerceIn(0, 100)
        }
    }
}

/** Which title a result notification gets. A private download's result never names its file. */
enum class DownloadResultTitle { DONE, FAILED, PRIVATE_DONE, PRIVATE_FAILED }

/** Title and text of the result notification for [entry]; [text] is null for a private download. */
class DownloadResultContent(val title: DownloadResultTitle, val text: String?) {
    // Deliberately no text: a file name must not end up in a log line.
    override fun toString(): String = "DownloadResultContent(title=$title)"

    companion object {
        fun from(entry: DownloadEntry): DownloadResultContent {
            val completed = entry.status == DownloadStatus.COMPLETED
            if (entry.isPrivate) {
                return DownloadResultContent(if (completed) DownloadResultTitle.PRIVATE_DONE else DownloadResultTitle.PRIVATE_FAILED, null)
            }
            return DownloadResultContent(if (completed) DownloadResultTitle.DONE else DownloadResultTitle.FAILED, entry.fileName)
        }
    }
}

/**
 * Finds downloads that settled (COMPLETED or FAILED) since the previous snapshot, each exactly once.
 *
 * Only an id that was IN_PROGRESS in the previous snapshot can be reported, so entries restored at process start
 * (never active in this process) and ones that appear already settled are ignored; CANCELLED is never reported.
 */
class DownloadSettledTracker {
    private var previousActive: Set<Long> = emptySet()

    fun update(entries: List<DownloadEntry>): List<DownloadEntry> {
        val settled =
            entries.filter {
                it.id in previousActive && (it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED)
            }
        previousActive = entries.filter { it.status == DownloadStatus.IN_PROGRESS }.map { it.id }.toSet()
        return settled
    }
}

package com.shibi.orbis.torrent

/**
 * When the engine reports a torrent finished (tor17-1). Pure: nothing here calls libtorrent.
 *
 * libtorrent posts its "finished" alert once, on a not-finished -> finished change. A torrent that misses it stays
 * DOWNLOADING in the list until it leaves the session. So three routes report it, and [FinishReports] lets only
 * the first one through each time the torrent is in the session:
 * - the alert, when [allRealFilesWanted] holds and every wanted byte is on disk;
 * - the approval, for a torrent that is already complete when its files are approved;
 * - the engine's once-a-second tick ([FinishReports.tick]), the net under the other two.
 */
object TorrentFinish {
    /**
     * Every file that is not a padding file is wanted. [wanted]: per file, its priority is not "do not download".
     * [isPad]: per file, it is a padding file (missing entries are not). libtorrent keeps every padding file at "do
     * not download" whatever the app asks (src/torrent.cpp, fix_priorities and file_priority, libtorrent 2.1.0.0),
     * so they are not counted. No real file at all (an empty list, or padding only): true, as for an empty torrent;
     * whether such a torrent is complete is then the engine's isComplete alone.
     */
    fun allRealFilesWanted(wanted: List<Boolean>, isPad: List<Boolean>): Boolean = wanted.indices.all { i -> wanted[i] || isPad.getOrElse(i) { false } }

    /** What the tick reads from one torrent's status. */
    data class TickFacts(
        /** Not paused. */
        val running: Boolean,
        /** Checking its files or its resume data. */
        val checking: Boolean,
        /** libtorrent's state is FINISHED or SEEDING. */
        val finishedOrSeeding: Boolean,
        /** Every wanted byte is on disk (the engine's isComplete). */
        val complete: Boolean,
    )

    /**
     * The tick reports a torrent that is approved (its files went through approveFiles: before that nothing is
     * wanted, and libtorrent calls such a torrent finished), not reported yet, running (a paused one waits for its
     * resume), not checking, finished by libtorrent's state AND complete on disk.
     *
     * Both signals, not either: libtorrent applies the approved priorities only when a disk job comes back
     * (src/torrent.cpp, prioritize_files and on_file_priority), so for a moment after the approval a torrent still has
     * the FINISHED state of its nothing-wanted phase with no byte wanted. The state alone would finish it empty.
     */
    fun tickReports(approved: Boolean, reported: Boolean, t: TickFacts): Boolean =
        approved && !reported && t.running && !t.checking && t.finishedOrSeeding && t.complete
}

/**
 * Which torrents of the session are approved and which were reported finished; keyed by engine id. Engine thread
 * only. A torrent's entries go when it leaves the session ([forget]) or the session stops ([clear]): added again, it
 * may be reported once more.
 */
class FinishReports {
    private val approved = HashSet<Long>()
    private val reported = HashSet<Long>()

    fun approve(key: Long) {
        approved += key
    }

    /** The alert or the approval reports [key]: true the first time in this session, false after any route did. */
    fun report(key: Long): Boolean = reported.add(key)

    /** The tick: true when [key] is to be reported now ([TorrentFinish.tickReports]); it counts as reported then. */
    fun tick(key: Long, t: TorrentFinish.TickFacts): Boolean = TorrentFinish.tickReports(key in approved, key in reported, t) && reported.add(key)

    fun forget(key: Long) {
        approved -= key
        reported -= key
    }

    fun clear() {
        approved.clear()
        reported.clear()
    }
}

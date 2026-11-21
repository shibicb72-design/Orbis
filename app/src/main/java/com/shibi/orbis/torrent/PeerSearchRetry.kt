package com.shibi.orbis.torrent

/**
 * Asks the DHT and the trackers again for a torrent that knows no peer (tor10-2).
 *
 * The tor10-1 baseline: a magnet that starts a new session often never gets a DHT lookup and then waits for
 * libtorrent's periodic announce (minutes); the same torrent from a .torrent file or resume data finds hundreds of
 * peers in about a second. So while a running torrent knows no peer, it asks again at [FIRST_MS] after it started
 * running, then every [EVERY_MS], and stops at the first known peer. When the DHT comes up, every such torrent asks it
 * at once ([dhtCameUp], [askDhtNow]).
 *
 * Engine thread only. Pure: the clock comes in as milliseconds on one monotonic clock; nothing here calls libtorrent.
 */
class PeerSearchRetry {
    enum class Call { DHT, TRACKERS }

    /** What the engine knows about one torrent now. */
    data class Torrent(
        /** Not paused. A paused torrent never asks. */
        val running: Boolean,
        /** Peers in its peer list plus peers connected; 0 = it knows none. */
        val peersKnown: Int,
        /** Its sources, from [TorrentDiscovery.of] (a trackers-only torrent: no DHT). */
        val discovery: TorrentDiscovery,
        /** The session's DHT node runs. */
        val dhtRunning: Boolean,
        val hasTrackers: Boolean,
    )

    /** One torrent's schedule: it has known no peer while running since [since]; [done] retry points are used. */
    private class Schedule(val since: Long) {
        var done = 0
    }

    private val schedules = HashMap<Long, Schedule>()
    private var dhtWasRunning = false

    /**
     * Once a second per torrent: the calls due at [now], each retry point once (points missed between two polls make
     * one retry). A torrent that is paused or knows a peer drops its schedule; it starts over from the next poll that
     * finds it running with no peer.
     */
    fun poll(key: Long, now: Long, t: Torrent): Set<Call> {
        if (!t.running || t.peersKnown > 0) {
            schedules.remove(key)
            return emptySet()
        }
        val s = schedules.getOrPut(key) { Schedule(now) }
        val ms = now - s.since
        if (!isDue(ms, t.peersKnown, s.done)) return emptySet()
        s.done = pointsPassed(ms)
        return calls(t)
    }

    /**
     * The session's DHT node is running now ([running]); true on a change from not running to running (the first
     * call of a session counts as a change when it runs).
     */
    fun dhtCameUp(running: Boolean): Boolean {
        val up = running && !dhtWasRunning
        dhtWasRunning = running
        return up
    }

    fun forget(key: Long) {
        schedules.remove(key)
    }

    /** The session stopped. */
    fun clear() {
        schedules.clear()
        dhtWasRunning = false
    }

    companion object {
        /** The first retries, in ms after the torrent started running with no peer known. */
        val FIRST_MS = longArrayOf(3_000, 8_000, 20_000, 45_000, 90_000)

        /** After the last of [FIRST_MS], one retry every this many ms. */
        const val EVERY_MS = 120_000L

        /** How many retry points lie at or before [msSinceStart]. */
        fun pointsPassed(msSinceStart: Long): Int {
            val first = FIRST_MS.count { it <= msSinceStart }
            if (first < FIRST_MS.size) return first
            return first + ((msSinceStart - FIRST_MS.last()) / EVERY_MS).toInt()
        }

        /**
         * The schedule: a retry is due at [msSinceStart] when the torrent knows no peer and a retry point has passed
         * that [done] (points already used) does not cover.
         */
        fun isDue(msSinceStart: Long, peersKnown: Int, done: Int): Boolean = peersKnown == 0 && pointsPassed(msSinceStart) > done

        /** What one retry asks: the DHT only if this torrent may use it and it runs, the trackers if it has any. Paused: nothing. */
        fun calls(t: Torrent): Set<Call> {
            if (!t.running) return emptySet()
            val calls = HashSet<Call>(2)
            if (t.discovery.dht && t.dhtRunning) calls += Call.DHT
            if (t.hasTrackers) calls += Call.TRACKERS
            return calls
        }

        /** When the DHT came up: whether [t] asks it at once (running, may use it, knows no peer). */
        fun askDhtNow(t: Torrent): Boolean = t.peersKnown == 0 && Call.DHT in calls(t)
    }
}

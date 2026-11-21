package com.shibi.orbis.torrent

/**
 * A short timeline per torrent of finding and connecting to peers, for the error log (tor10-1). It measures; it
 * changes nothing about how a torrent runs.
 *
 * Engine thread only. Pure: every time comes in as milliseconds on one monotonic clock, and every line goes to [log].
 * A line carries fixed words and numbers only (the engine id, ms since "added", counts): never a name, info hash,
 * tracker or address, so a private-tab torrent gets the same timeline and nothing in it identifies the torrent.
 *
 * Lines:
 * - one per milestone, at its first event: `timeline <id> t=<ms> <step>`; later events only count.
 * - a summary at 60 s and 300 s after "added", and when the torrent finishes or is removed:
 *   `timeline <id> t=<ms> <at_60s|at_300s|finished|removed> <milestone>=<ms>... <counter>=<n>...`
 *   (a milestone not reached yet, and a DHT checkpoint not taken yet, is left out).
 * - per session: `listen ok tcp v4` (never the address), `external v4=yes v6=no` (never the address).
 */
class TorrentTimeline(private val log: (String) -> Unit) {
    enum class Step(val word: String) {
        SESSION_STARTED("session_started"),
        DHT_READY("dht_ready"),
        TRACKER_ANSWER("tracker_answer"),
        PEER_FOUND("peer_found"),
        PEER_CONNECTED("peer_connected"),
        DETAILS("details"),
        RESUMED("resumed"),
        FIRST_BYTE("first_byte"),
    }

    enum class TrackerFailure { TIMEOUT, REFUSED, OTHER }

    enum class ConnectFailure { TIMEOUT, REFUSED, NO_ROUTE, OTHER }

    /** Where [peersFound] came from. Peer exchange has no alert: see [sample]. */
    enum class PeerSource { TRACKER, DHT, LOCAL }

    private class Record(val addedAt: Long, val newSession: Boolean) {
        val steps = LongArray(Step.entries.size) { NOT_YET }
        var trackerAsks = 0
        var trackerAnswers = 0
        val trackerFailures = IntArray(TrackerFailure.entries.size)
        val found = IntArray(PeerSource.entries.size)
        var attempts = 0
        val connectFailures = IntArray(ConnectFailure.entries.size)
        var dropped = 0
        var incoming = 0
        val dhtNodes = IntArray(DHT_CHECKPOINTS_S.size) { -1 }

        // The last sample (see [sample]); -1 = never sampled.
        var connectedNow = -1
        var connectingNow = -1
        var pexConnected = -1
        var known = -1
        var at60 = false
        var at300 = false
        var finished = false
    }

    private val records = LinkedHashMap<Long, Record>()

    // Session-wide; reset by [sessionEnded].
    private var dhtReadyAt = NOT_YET
    private var latestDhtNodes = -1
    private val listenSeen = HashSet<String>()
    private var externalV4 = false
    private var externalV6 = false

    /** A torrent is in the session from [at] on. [sessionStartedAt]: when this add built the session; null = it was running. */
    fun added(id: Long, at: Long, sessionStartedAt: Long?) {
        if (id in records) return
        records[id] = Record(at, newSession = sessionStartedAt != null)
        milestone(id, Step.SESSION_STARTED, sessionStartedAt ?: at)
        if (dhtReadyAt != NOT_YET) milestone(id, Step.DHT_READY, at)
    }

    /** The session's DHT finished its bootstrap: a milestone for every torrent in it now, and for every one added later. */
    fun dhtBootstrapped(at: Long) {
        if (dhtReadyAt == NOT_YET) dhtReadyAt = at
        records.keys.toList().forEach { milestone(it, Step.DHT_READY, at) }
    }

    fun trackerAsked(id: Long) {
        records[id]?.let { it.trackerAsks++ }
    }

    fun trackerAnswered(id: Long, peers: Int, at: Long) {
        val r = records[id] ?: return
        r.trackerAnswers++
        r.found[PeerSource.TRACKER.ordinal] += peers.coerceAtLeast(0)
        milestone(id, Step.TRACKER_ANSWER, at)
        if (peers > 0) milestone(id, Step.PEER_FOUND, at)
    }

    fun trackerFailed(id: Long, kind: TrackerFailure) {
        records[id]?.let { it.trackerFailures[kind.ordinal]++ }
    }

    /** [source] answered with [peers] peers (a DHT reply, a local peer: 1). */
    fun peersFound(id: Long, source: PeerSource, peers: Int, at: Long) {
        val r = records[id] ?: return
        r.found[source.ordinal] += peers.coerceAtLeast(0)
        if (peers > 0) milestone(id, Step.PEER_FOUND, at)
    }

    /** An outgoing connection was started: the peer came from somewhere (peer exchange too). */
    fun connectAttempt(id: Long, at: Long) {
        val r = records[id] ?: return
        r.attempts++
        milestone(id, Step.PEER_FOUND, at)
    }

    fun incomingConnection(id: Long, at: Long) {
        val r = records[id] ?: return
        r.incoming++
        milestone(id, Step.PEER_FOUND, at)
    }

    fun connectFailed(id: Long, kind: ConnectFailure) {
        records[id]?.let { it.connectFailures[kind.ordinal]++ }
    }

    /** A peer went away after its connection was made (handshake failures included). */
    fun disconnected(id: Long) {
        records[id]?.let { it.dropped++ }
    }

    fun milestone(id: Long, step: Step, at: Long) {
        val r = records[id] ?: return
        if (r.steps[step.ordinal] != NOT_YET) return
        val t = since(r, at)
        r.steps[step.ordinal] = t
        log("timeline $id t=$t ${step.word}")
    }

    /**
     * What the peer list shows at [at]: peers past the handshake ([connected]), outgoing ones still connecting,
     * connected ones that came from peer exchange, and every peer the torrent knows. The first [connected] > 0 is the
     * "peer connected" milestone.
     */
    fun sample(id: Long, at: Long, connected: Int, connecting: Int, pexConnected: Int, known: Int) {
        val r = records[id] ?: return
        r.connectedNow = connected
        r.connectingNow = connecting
        r.pexConnected = pexConnected
        r.known = known
        if (connected > 0) {
            milestone(id, Step.PEER_FOUND, at)
            milestone(id, Step.PEER_CONNECTED, at)
        }
    }

    /** True when the engine should [sample] [id] before the next [tick]: no peer connected yet, or a summary is due. */
    fun wantsSample(id: Long, at: Long): Boolean {
        val r = records[id] ?: return false
        val t = since(r, at)
        return (r.steps[Step.PEER_CONNECTED.ordinal] == NOT_YET && t < LONG_SUMMARY_MS) ||
            (t >= SHORT_SUMMARY_MS && !r.at60) ||
            (t >= LONG_SUMMARY_MS && !r.at300)
    }

    /** The session's DHT node count, as last read. */
    fun dhtNodes(nodes: Int) {
        latestDhtNodes = nodes
    }

    /** True while some torrent still has a DHT checkpoint to take: the engine then reads the node count. */
    fun wantsDhtNodes(at: Long): Boolean = records.values.any { r -> r.dhtNodes.last() < 0 && since(r, at) < DHT_CHECKPOINTS_S.last() * 1000L + 5_000L }

    /** Once a second: DHT checkpoints and the 60 s and 300 s summaries that are due. */
    fun tick(at: Long) {
        for ((id, r) in records) {
            val t = since(r, at)
            if (latestDhtNodes >= 0) {
                DHT_CHECKPOINTS_S.forEachIndexed { i, s -> if (r.dhtNodes[i] < 0 && t >= s * 1000L) r.dhtNodes[i] = latestDhtNodes }
            }
            if (t >= SHORT_SUMMARY_MS && !r.at60) {
                r.at60 = true
                summary(id, r, t, "at_60s")
            }
            if (t >= LONG_SUMMARY_MS && !r.at300) {
                r.at300 = true
                summary(id, r, t, "at_300s")
            }
        }
    }

    /** All wanted bytes are on disk. Summarised once. */
    fun finished(id: Long, at: Long) {
        val r = records[id] ?: return
        if (r.finished) return
        r.finished = true
        summary(id, r, since(r, at), "finished")
    }

    /** The torrent left the session: its last summary; the record goes. */
    fun removed(id: Long, at: Long) {
        val r = records.remove(id) ?: return
        summary(id, r, since(r, at), "removed")
    }

    /** One line per listen result, protocol and address family, once per session. [socket]: a fixed protocol word. */
    fun listen(ok: Boolean, socket: String, v6: Boolean) {
        val line = "listen ${if (ok) "ok" else "failed"} ${word(socket)} ${if (v6) "v6" else "v4"}"
        if (listenSeen.add(line)) log(line)
    }

    /** The session learned its external address of one family (never logged): logged when a family is new. */
    fun externalAddress(v6: Boolean) {
        if (if (v6) externalV6 else externalV4) return
        if (v6) externalV6 = true else externalV4 = true
        log("external v4=${yesNo(externalV4)} v6=${yesNo(externalV6)}")
    }

    /** The session stopped: every record and session fact goes, with no summary. */
    fun sessionEnded() {
        records.clear()
        dhtReadyAt = NOT_YET
        latestDhtNodes = -1
        listenSeen.clear()
        externalV4 = false
        externalV6 = false
    }

    private fun summary(id: Long, r: Record, t: Long, what: String) {
        val b = StringBuilder("timeline $id t=$t $what new_session=${if (r.newSession) 1 else 0}")
        Step.entries.forEach { s -> if (r.steps[s.ordinal] != NOT_YET) b.append(' ').append(s.word).append('=').append(r.steps[s.ordinal]) }
        b.append(" tracker_asks=").append(r.trackerAsks)
        b.append(" tracker_answers=").append(r.trackerAnswers)
        TrackerFailure.entries.forEach { k -> b.append(" tracker_").append(k.name.lowercase()).append('=').append(r.trackerFailures[k.ordinal]) }
        PeerSource.entries.forEach { s -> b.append(" found_").append(s.name.lowercase()).append('=').append(r.found[s.ordinal]) }
        b.append(" attempts=").append(r.attempts)
        val failed = r.connectFailures.sum()
        // An attempt that did not fail and is not still connecting got its connection.
        if (r.connectingNow >= 0) b.append(" connected=").append((r.attempts - failed - r.connectingNow).coerceAtLeast(0))
        ConnectFailure.entries.forEach { k -> b.append(" fail_").append(k.name.lowercase()).append('=').append(r.connectFailures[k.ordinal]) }
        b.append(" dropped=").append(r.dropped)
        b.append(" incoming=").append(r.incoming)
        if (r.connectedNow >= 0) {
            b.append(" peers_now=").append(r.connectedNow)
            b.append(" connecting_now=").append(r.connectingNow)
            b.append(" pex_now=").append(r.pexConnected)
            b.append(" known=").append(r.known)
        }
        DHT_CHECKPOINTS_S.forEachIndexed { i, s -> if (r.dhtNodes[i] >= 0) b.append(" dht_nodes_").append(s).append("s=").append(r.dhtNodes[i]) }
        log(b.toString())
    }

    private fun since(r: Record, at: Long): Long = (at - r.addedAt).coerceAtLeast(0)

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    companion object {
        private const val NOT_YET = -1L
        const val SHORT_SUMMARY_MS = 60_000L
        const val LONG_SUMMARY_MS = 300_000L
        val DHT_CHECKPOINTS_S = intArrayOf(5, 15, 30, 60)

        /** A fixed word, kept to lower-case letters, digits and '_' (an enum name, for example). */
        fun word(s: String): String = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.ifEmpty { "unknown" }

        /** Kind of a tracker failure from its error text (read, never logged). */
        fun trackerFailure(message: String?): TrackerFailure {
            val m = message.orEmpty().lowercase()
            return when {
                "timed out" in m || "timeout" in m -> TrackerFailure.TIMEOUT
                "refused" in m -> TrackerFailure.REFUSED
                else -> TrackerFailure.OTHER
            }
        }

        /** Kind of a failed connection attempt from its error text (read, never logged). */
        fun connectFailure(message: String?): ConnectFailure {
            val m = message.orEmpty().lowercase()
            return when {
                "timed out" in m || "timeout" in m -> ConnectFailure.TIMEOUT
                "refused" in m -> ConnectFailure.REFUSED
                "unreachable" in m || "no route" in m -> ConnectFailure.NO_ROUTE
                else -> ConnectFailure.OTHER
            }
        }
    }
}

package com.shibi.orbis.cast

/** How long a device may stay in [CastPhase.Connecting] before the attempt is failed and the route dropped. */
internal const val CONNECT_TIMEOUT_MS = 15_000L

/** How long a page probe may stay unanswered before Cast continues with the address alone. */
internal const val PROBE_TIMEOUT_MS = 1_500L

private fun sticky(notice: CastNotice?): Boolean =
    notice == CastNotice.FAILED ||
        notice == CastNotice.YOUTUBE_NEEDS_GOOGLE ||
        notice == CastNotice.YOUTUBE_UNCONFIRMED ||
        notice == CastNotice.REJECTED

/**
 * Cast sheet transitions that do not touch the network. [CastCoordinator] performs the device work these decisions
 * name: a connect that outlives [CONNECT_TIMEOUT_MS] is failed and the session is closed, a start that arrives after
 * the sheet has left Connecting is an orphan and is stopped, and a private tab closing drops the in-memory target.
 */
internal class CastFlow {
    var phase: CastPhase = CastPhase.Idle
        private set

    var notice: CastNotice? = null
        private set

    /** The page that was classified. Cleared when the private tab it came from is closed. */
    var target: CastTarget? = null

    /** Tab id while [target] came from a private tab. Null for a normal tab. */
    var privateOrigin: Long? = null
        private set

    private var connectGeneration = 0
    private var connectStartedAt = 0L

    /**
     * A page change while a device is still connecting must not replace the attempt.
     * Returns false when the caller should leave the current phase alone.
     */
    fun present(hasPlayback: Boolean): Boolean {
        if (phase == CastPhase.Connecting) return false
        if (hasPlayback) {
            phase = CastPhase.Controls
            notice = null
            return false
        }
        return true
    }

    fun rememberOrigin(tabId: Long, isPrivate: Boolean) {
        privateOrigin = if (isPrivate) tabId else null
    }

    fun showNoMedia() {
        phase = CastPhase.NoMedia
        notice = CastNotice.NO_MEDIA
    }

    fun showPicker(youtube: Boolean, searching: Boolean) {
        phase = CastPhase.Picker(youtube = youtube, searching = searching)
        notice = null
    }

    fun updateSearching(searching: Boolean, devicesEmpty: Boolean) {
        val current = phase as? CastPhase.Picker ?: return
        phase = current.copy(searching = searching && devicesEmpty)
        if (!searching && devicesEmpty && !sticky(notice)) notice = CastNotice.NO_DEVICES
    }

    fun showNotice(value: CastNotice?) {
        notice = value
    }

    fun beginConnect(nowMs: Long): Int {
        connectGeneration += 1
        connectStartedAt = nowMs
        phase = CastPhase.Connecting
        notice = null
        return connectGeneration
    }

    /** True when [generation] is still the live attempt and the deadline has passed. */
    fun connectExpired(generation: Int, nowMs: Long, timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean {
        if (generation != connectGeneration || phase != CastPhase.Connecting) return false
        return nowMs - connectStartedAt >= timeoutMs
    }

    fun failConnect(generation: Int, youtube: Boolean, failure: CastNotice = CastNotice.FAILED): Boolean {
        if (generation != connectGeneration || phase != CastPhase.Connecting) return false
        phase = CastPhase.Picker(youtube = youtube, searching = false)
        notice = failure
        return true
    }

    /**
     * True when this start still belongs to the live attempt. False is an orphan: the device accepted the media after
     * the sheet moved on, and the caller stops it.
     */
    fun acceptStart(generation: Int): Boolean {
        if (generation != connectGeneration || phase != CastPhase.Connecting) return false
        phase = CastPhase.Controls
        notice = null
        return true
    }

    /** Drops a picker that belonged to a private tab which closed before anything was sent. */
    fun clearIdle() {
        connectGeneration += 1
        phase = CastPhase.Idle
        notice = null
    }

    /** The sheet closed. A live connect or playback stays put. */
    fun dismissSheet(hasPlayback: Boolean) {
        if (phase == CastPhase.Connecting) return
        if (phase == CastPhase.Controls && hasPlayback) return
        phase = CastPhase.Idle
        notice = null
    }

    /** Bumps the connect generation so a late timeout or start cannot revive this attempt. */
    fun invalidateConnect() {
        connectGeneration += 1
        if (phase == CastPhase.Connecting || phase == CastPhase.Controls) phase = CastPhase.Idle
    }

    /**
     * The private tab that owned the current target was closed. The target is dropped.
     * Null when [tabId] is not that tab. Otherwise true when a connect or playback from it must be stopped.
     */
    fun privateTabClosed(tabId: Long): Boolean? {
        if (privateOrigin != tabId) return null
        val stop = phase == CastPhase.Connecting || phase == CastPhase.Controls
        target = null
        privateOrigin = null
        return stop
    }
}

/**
 * One in-flight page probe. A reply or the timeout delivers at most once; a newer probe invalidates the previous one.
 */
internal class ProbeGate(private val timeoutMs: Long = PROBE_TIMEOUT_MS) {
    private var generation = 0
    private var waiting = false
    private var startedAt = 0L

    @Synchronized
    fun start(nowMs: Long): Int {
        generation += 1
        waiting = true
        startedAt = nowMs
        return generation
    }

    /** Consumes the wait. False when this generation already finished or was replaced. */
    @Synchronized
    fun take(generation: Int): Boolean {
        if (!waiting || generation != this.generation) return false
        waiting = false
        return true
    }

    /** True when the wait is still open and [nowMs] is at or past the timeout. Does not consume the wait. */
    @Synchronized
    fun timedOut(generation: Int, nowMs: Long): Boolean {
        if (!waiting || generation != this.generation) return false
        return nowMs - startedAt >= timeoutMs
    }
}

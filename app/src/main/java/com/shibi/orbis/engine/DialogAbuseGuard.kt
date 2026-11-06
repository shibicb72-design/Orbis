package com.shibi.orbis.engine

/**
 * Stops a page from trapping the user in an endless alert/confirm/prompt loop. Pure, with an injected clock.
 * The first [maxPrompts] prompts inside [windowMillis] are shown; the next one trips the guard, and from then on
 * every prompt is auto-dismissed until the next top-level page start ([onPageStart]). Main thread only.
 */
class DialogAbuseGuard(
    private val now: () -> Long,
    private val maxPrompts: Int = 5,
    private val windowMillis: Long = 10_000,
) {
    private val times = ArrayDeque<Long>()
    private var tripped = false

    /** Records a prompt. True means "dismiss it without showing". */
    fun shouldSuppress(): Boolean {
        if (tripped) return true
        val t = now()
        while (times.isNotEmpty() && t - times.first() >= windowMillis) times.removeFirst()
        if (times.size >= maxPrompts) {
            tripped = true
            return true
        }
        times.addLast(t)
        return false
    }

    /** A new top-level page started: forget everything. */
    fun onPageStart() {
        times.clear()
        tripped = false
    }
}

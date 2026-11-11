package com.shibi.orbis.engine

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull

/** Limits for [providerCall]. */
object ProviderDeadline {
    const val DEFAULT_TIMEOUT_MS = 3_000L

    /** Stuck provider threads cannot be stopped, only abandoned: never more than this many alive at once. */
    const val MAX_THREADS = 8

    const val THREAD_NAME = "provider-call"
    const val LOG_TAG = "Provider"
    const val TIMEOUT_LOG = "provider call timed out"

    private val alive = AtomicInteger(0)

    /** Provider threads still running, including abandoned ones. */
    internal fun aliveThreads(): Int = alive.get()

    internal fun tryAcquire(): Boolean {
        if (alive.incrementAndGet() > MAX_THREADS) {
            alive.decrementAndGet()
            return false
        }
        return true
    }

    internal fun release() {
        alive.decrementAndGet()
    }
}

private sealed interface ProviderOutcome<out T> {
    class Value<T>(val value: T) : ProviderOutcome<T>

    data object Failed : ProviderOutcome<Nothing>

    /** The caller gave up (timeout or cancellation) before the block finished. */
    data object Abandoned : ProviderOutcome<Nothing>
}

/**
 * Runs [block] (a call into another app's content provider) on its own daemon thread and waits at most [timeoutMs]
 * for it, without blocking a dispatcher thread while waiting. Returns null on timeout, on any exception, and at once
 * when [ProviderDeadline.MAX_THREADS] provider threads are already alive. A result that arrives after the caller gave
 * up is dropped; a late [Closeable] result is closed.
 *
 * [log] gets only [ProviderDeadline.TIMEOUT_LOG].
 */
suspend fun <T> providerCall(
    timeoutMs: Long = ProviderDeadline.DEFAULT_TIMEOUT_MS,
    log: (String) -> Unit = {},
    block: () -> T,
): T? = providerCallOn(timeoutMs, log, ::Thread, block)

/** [providerCall] with the thread constructor as a seam, so a test can make building the thread fail. */
@OptIn(ExperimentalCoroutinesApi::class) // getCompleted, only after complete() returned false
internal suspend fun <T> providerCallOn(
    timeoutMs: Long,
    log: (String) -> Unit,
    newThread: (Runnable, String) -> Thread,
    block: () -> T,
): T? {
    val outcome = CompletableDeferred<ProviderOutcome<T>>()
    if (!ProviderDeadline.tryAcquire()) return null
    // Building the thread can throw as well as starting it (OutOfMemoryError): both inside the try, so the slot is
    // never leaked. When either throws the body never runs, so this catch is the only release.
    try {
        val worker =
            newThread({
                try {
                    val result: ProviderOutcome<T> =
                        try {
                            ProviderOutcome.Value(block())
                        } catch (t: Throwable) {
                            ProviderOutcome.Failed
                        }
                    if (!outcome.complete(result) && result is ProviderOutcome.Value) closeQuietly(result.value)
                } finally {
                    ProviderDeadline.release()
                }
            }, ProviderDeadline.THREAD_NAME)
        worker.isDaemon = true
        worker.start()
    } catch (t: Throwable) {
        ProviderDeadline.release()
        return null
    }
    var delivered = false
    try {
        val finished = withTimeoutOrNull(timeoutMs) { outcome.await() }
        if (finished != null) {
            delivered = true
            return finished.valueOrNull()
        }
        if (outcome.complete(ProviderOutcome.Abandoned)) {
            log(ProviderDeadline.TIMEOUT_LOG)
            return null
        }
        // The block finished in the instant between the timeout and the hand-over: use what it gave.
        delivered = true
        return outcome.getCompleted().valueOrNull()
    } finally {
        // Cancelled by the caller while waiting: abandon the call, and close a value that slipped in.
        if (!delivered && !outcome.complete(ProviderOutcome.Abandoned)) {
            val late = outcome.getCompleted()
            if (late is ProviderOutcome.Value) closeQuietly(late.value)
        }
    }
}

private fun <T> ProviderOutcome<T>.valueOrNull(): T? = if (this is ProviderOutcome.Value) value else null

private fun closeQuietly(value: Any?) {
    if (value is Closeable) runCatching { value.close() }
}

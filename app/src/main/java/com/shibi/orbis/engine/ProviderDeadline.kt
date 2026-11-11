package com.shibi.orbis.engine

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A cap on provider threads alive at once. Stuck provider threads cannot be stopped, only abandoned, and a slot is
 * held until the provider returns, however long that is.
 */
class ProviderPool(val max: Int = ProviderDeadline.MAX_THREADS) {
    private val alive = AtomicInteger(0)

    /** Provider threads of this pool still running, including abandoned ones. */
    internal fun aliveThreads(): Int = alive.get()

    internal fun tryAcquire(): Boolean {
        if (alive.incrementAndGet() > max) {
            alive.decrementAndGet()
            return false
        }
        return true
    }

    internal fun release() {
        alive.decrementAndGet()
    }
}

/** Limits for [providerCall]. */
object ProviderDeadline {
    const val DEFAULT_TIMEOUT_MS = 3_000L

    /** Threads per [ProviderPool]. */
    const val MAX_THREADS = 8

    const val THREAD_NAME = "provider-call"
    const val LOG_TAG = "Provider"
    const val TIMEOUT_LOG = "provider call timed out"

    /**
     * Calls another app can cause without the user picking anything: `getType` on a shared or viewed URI, the
     * torrent sheet's size query and open, the shared-document import.
     */
    val intents = ProviderPool()

    /**
     * Calls on a document the user picked (uploads). Separate, so a hostile app that fills [intents] with calls that
     * never return cannot turn off file upload.
     */
    val picked = ProviderPool()

    /** Provider threads still running in every pool, including abandoned ones. */
    internal fun aliveThreads(): Int = intents.aliveThreads() + picked.aliveThreads()
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
 * when [pool] is full. A result that arrives after the caller gave
 * up is dropped; a late [Closeable] result is closed.
 *
 * [log] gets only [ProviderDeadline.TIMEOUT_LOG].
 */
suspend fun <T> providerCall(
    timeoutMs: Long = ProviderDeadline.DEFAULT_TIMEOUT_MS,
    log: (String) -> Unit = {},
    pool: ProviderPool = ProviderDeadline.intents,
    block: () -> T,
): T? = providerCallOn(timeoutMs, log, ::Thread, pool, block)

/** [providerCall] with the thread constructor as a seam, so a test can make building the thread fail. */
@OptIn(ExperimentalCoroutinesApi::class) // getCompleted, only after complete() returned false
internal suspend fun <T> providerCallOn(
    timeoutMs: Long,
    log: (String) -> Unit,
    newThread: (Runnable, String) -> Thread,
    pool: ProviderPool = ProviderDeadline.intents,
    block: () -> T,
): T? {
    val outcome = CompletableDeferred<ProviderOutcome<T>>()
    if (!pool.tryAcquire()) return null
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
                    pool.release()
                }
            }, ProviderDeadline.THREAD_NAME)
        worker.isDaemon = true
        worker.start()
    } catch (t: Throwable) {
        pool.release()
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

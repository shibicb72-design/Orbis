package com.shibi.orbis.torrent

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.engine.ProviderDeadline
import com.shibi.orbis.engine.providerCall
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads a .torrent file for the confirm sheet: a web response's body or another app's `content:` URI. Off the main
 * thread ([io]); never touches the engine.
 *
 * At most [TorrentLimits.MAX_TORRENT_FILE_BYTES]: a declared size above that is refused before anything is read, and
 * one byte more than that while reading throws everything away. Idle and overall timeouts close the stream. The stream
 * is always closed. A response that is not read to the end is discarded exactly once, on every failure path.
 */
class TorrentIntakeReader(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val overallTimeoutMs: Long = OVERALL_TIMEOUT_MS,
    private val providerTimeoutMs: Long = ProviderDeadline.DEFAULT_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) {
    sealed interface Result {
        class Ok(val bytes: ByteArray) : Result {
            override fun toString(): String = "Ok"
        }

        class Failed(val reason: IntakeRefusal) : Result {
            override fun toString(): String = "Failed(${reason.name})"
        }
    }

    suspend fun readResponse(download: IncomingDownload): Result {
        var ended = false
        fun discardOnce() {
            if (!ended) {
                ended = true
                runCatching { download.discard() }
            }
        }
        if (download.contentLength > TorrentLimits.MAX_TORRENT_FILE_BYTES) {
            discardOnce()
            return Result.Failed(IntakeRefusal.TOO_LARGE)
        }
        try {
            val result = read { download.openBody() }
            if (result !is Result.Ok) discardOnce()
            return result
        } catch (e: CancellationException) {
            discardOnce()
            throw e
        }
    }

    suspend fun readUri(resolver: ContentResolver, uri: Uri): Result =
        readWithDeclaredSize(
            querySize = {
                resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else -1L
                }
            },
            open = { resolver.openInputStream(uri) },
        )

    /**
     * The size query and the open are provider calls with their own deadline and the shared thread cap
     * ([providerCall]), so a provider that hangs in either cannot pin an IO thread; the confirm sheet starts this read
     * before any tap. A hung or failed query means "size unknown" (-1), and the capped read with its watchdog still
     * enforces the limit. A hung or failed open fails the read; a stream that opens after the deadline is closed.
     */
    internal suspend fun readWithDeclaredSize(querySize: () -> Long?, open: () -> InputStream?): Result {
        val declared = providerCall(providerTimeoutMs, log, querySize) ?: -1L
        if (declared > TorrentLimits.MAX_TORRENT_FILE_BYTES) return Result.Failed(IntakeRefusal.TOO_LARGE)
        return read { providerCall(providerTimeoutMs, log, open) }
    }

    /**
     * The blocking read runs on its own job so a stream that ignores [InputStream.close] cannot hold the caller past the
     * timeouts; the first of "read finished" and "timed out" decides. [open] may suspend; a stream it returns after the
     * read already ended is closed here.
     */
    internal suspend fun read(open: suspend () -> InputStream?): Result =
        // On [io] as a whole, so the watchdog's timing never depends on the caller's dispatcher (a test clock, a frame).
        withContext(io) {
            val outcome = CompletableDeferred<Result>()
            val stream = AtomicReference<InputStream?>(null)
            val lastProgress = AtomicLong(System.nanoTime())
            val reader = CoroutineScope(io + SupervisorJob())
            reader.launch {
                val r =
                    try {
                        val input = open()
                        if (input == null) {
                            Result.Failed(IntakeRefusal.READ_FAILED)
                        } else {
                            stream.set(input)
                            // Timed out while the stream was being opened: nobody else will close it.
                            if (outcome.isCompleted) closeQuietly(input)
                            input.use { copyCapped(it, lastProgress) }
                        }
                    } catch (e: Exception) {
                        Result.Failed(IntakeRefusal.READ_FAILED)
                    }
                outcome.complete(r)
            }
            val started = System.nanoTime()
            val watchdog =
                launch {
                    while (!outcome.isCompleted) {
                        delay(minOf(idleTimeoutMs, overallTimeoutMs, 250L).coerceAtLeast(1L))
                        val now = System.nanoTime()
                        val idle = (now - lastProgress.get()) / 1_000_000 >= idleTimeoutMs
                        val overall = (now - started) / 1_000_000 >= overallTimeoutMs
                        if (idle || overall) {
                            outcome.complete(Result.Failed(IntakeRefusal.TIMEOUT))
                            stream.get()?.let(::closeQuietly)
                        }
                    }
                }
            try {
                outcome.await()
            } finally {
                watchdog.cancel()
                // Cancelled by the caller (the sheet went away): stop the read too.
                if (!outcome.isCompleted) {
                    outcome.complete(Result.Failed(IntakeRefusal.READ_FAILED))
                    stream.get()?.let(::closeQuietly)
                }
            }
        }

    private fun copyCapped(input: InputStream, lastProgress: AtomicLong): Result {
        val cap = TorrentLimits.MAX_TORRENT_FILE_BYTES
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER_BYTES)
        while (true) {
            // One byte over the cap is enough to know: never ask for more than that.
            val want = minOf(buf.size, cap + 1 - out.size())
            val n = input.read(buf, 0, want)
            if (n < 0) break
            lastProgress.set(System.nanoTime())
            out.write(buf, 0, n)
            if (out.size() > cap) return Result.Failed(IntakeRefusal.TOO_LARGE)
        }
        if (out.size() == 0) return Result.Failed(IntakeRefusal.READ_FAILED)
        return Result.Ok(out.toByteArray())
    }

    private fun closeQuietly(s: InputStream) {
        runCatching { s.close() }
    }

    companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val IDLE_TIMEOUT_MS = 30_000L
        const val OVERALL_TIMEOUT_MS = 60_000L
    }
}

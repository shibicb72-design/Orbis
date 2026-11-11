package com.shibi.orbis.ui.passwords

import com.shibi.orbis.engine.ProviderDeadline
import com.shibi.orbis.engine.ProviderPool
import com.shibi.orbis.engine.UPLOAD_OPEN_TIMEOUT_MS
import com.shibi.orbis.engine.providerCall
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** How long the provider of a picked CSV may take to open it (cloud providers fetch the whole file first). */
internal const val PICKED_OPEN_TIMEOUT_MS = UPLOAD_OPEN_TIMEOUT_MS

/** How long reading or writing a picked CSV (at most [MAX_IMPORT_BYTES]) may take, in all. */
internal const val PICKED_TRANSFER_TIMEOUT_MS = 120_000L

/** How long closing an abandoned stream may take before its thread is left to it. */
private const val PICKED_CLOSE_TIMEOUT_MS = 1_000L

/**
 * Reads the CSV the user picked, at most [cap] bytes. The provider is another app and may hang: the open and the read
 * each run through [providerCall] in the [ProviderDeadline.picked] pool, never on a dispatcher thread, and a read
 * that outlives its deadline has its stream closed (which wakes a blocked read). Null when the file could not be
 * opened or read in time, or is larger than [cap].
 */
internal suspend fun readPickedText(
    open: () -> InputStream?,
    cap: Int,
    openTimeoutMs: Long = PICKED_OPEN_TIMEOUT_MS,
    transferTimeoutMs: Long = PICKED_TRANSFER_TIMEOUT_MS,
    pool: ProviderPool = ProviderDeadline.picked,
): String? {
    val input = providerCall(openTimeoutMs, pool = pool, block = open) ?: return null
    return try {
        providerCall(transferTimeoutMs, pool = pool) { input.readBytesCapped(cap) }?.toString(Charsets.UTF_8)
    } finally {
        closeOffThread(input, pool)
    }
}

/**
 * Writes [bytes] to the document the user picked, with the same deadlines as [readPickedText]. True only when the
 * provider took every byte and closed the stream in time.
 */
internal suspend fun writePickedBytes(
    open: () -> OutputStream?,
    bytes: ByteArray,
    openTimeoutMs: Long = PICKED_OPEN_TIMEOUT_MS,
    transferTimeoutMs: Long = PICKED_TRANSFER_TIMEOUT_MS,
    pool: ProviderPool = ProviderDeadline.picked,
): Boolean {
    val output = providerCall(openTimeoutMs, pool = pool, block = open) ?: return false
    val written =
        providerCall(transferTimeoutMs, pool = pool) {
            output.use { it.write(bytes) }
            true
        } == true
    // After a timeout or failure the stream may still be open; closing a closed stream does nothing.
    if (!written) closeOffThread(output, pool)
    return written
}

/** Closes [stream] on a provider thread: a provider may block in close() as well. */
private suspend fun closeOffThread(stream: Closeable, pool: ProviderPool) {
    providerCall(PICKED_CLOSE_TIMEOUT_MS, pool = pool) { stream.close() }
}

/** The stream's bytes, or null when it is larger than [cap]. */
private fun InputStream.readBytesCapped(cap: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > cap) return null
    }
    return out.toByteArray()
}

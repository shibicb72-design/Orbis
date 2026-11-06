package com.shibi.orbis.bookmarks

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads [input] to the end, or returns null as soon as it turns out to hold more than [maxBytes] bytes (at most
 * maxBytes + 1 are ever read). Blocking: run it off the main thread. Does not close [input].
 */
fun readCapped(input: InputStream, maxBytes: Int = MAX_IMPORT_BYTES): ByteArray? {
    val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val want = minOf(buffer.size.toLong(), maxBytes + 1L - total).toInt()
        val read = input.read(buffer, 0, want)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
}

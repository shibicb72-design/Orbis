package com.shibi.orbis.engine

import java.io.InputStream

/**
 * A response the engine will not render. The body is live until [openBody] is consumed or [discard] is called,
 * so every instance must end in exactly one of those two.
 */
interface IncomingDownload {
    val url: String
    val contentDisposition: String?
    val contentType: String?
    /** -1 when unknown. */
    val contentLength: Long

    /**
     * Where in the file this body starts: 0 normally; the requested offset when the server honoured a range request
     * (a resumed download). [contentLength] is then the length of the rest.
     */
    val startOffset: Long get() = 0L

    /** Null when the engine has no body. The caller closes the stream. Read it off the main thread. */
    fun openBody(): InputStream?

    fun discard()
}

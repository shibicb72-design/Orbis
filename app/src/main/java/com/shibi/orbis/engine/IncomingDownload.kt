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

    /** Null when the engine has no body. The caller closes the stream. Read it off the main thread. */
    fun openBody(): InputStream?

    fun discard()
}

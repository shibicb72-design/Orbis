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

    /**
     * What identifies this version of the file for a later resume (`If-Range`): a strong ETag, else Last-Modified;
     * null when the response carries neither (see [resumeValidator]).
     */
    val validator: String? get() = null

    /** Null when the engine has no body. The caller closes the stream. Read it off the main thread. */
    fun openBody(): InputStream?

    fun discard()
}

/**
 * The `If-Range` value for a response with these headers: a strong [etag], else [lastModified], else null. A weak ETag
 * cannot be used with If-Range, and a value with a line break or an absurd length is not sent back.
 */
internal fun resumeValidator(etag: String?, lastModified: String?): String? {
    fun clean(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 && it.none { c -> c < ' ' } }
    val strong = clean(etag)?.takeIf { !it.startsWith("W/", ignoreCase = true) }
    return strong ?: clean(lastModified)
}

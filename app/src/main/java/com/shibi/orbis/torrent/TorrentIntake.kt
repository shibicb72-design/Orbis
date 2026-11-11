package com.shibi.orbis.torrent

import com.shibi.orbis.downloads.DownloadFileNames
import com.shibi.orbis.engine.IncomingDownload
import com.shibi.orbis.engine.contentProviderIsThisApp
import com.shibi.orbis.engine.schemeOf
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Something that may become a torrent once the user taps Download in the confirm sheet. Nothing here has been handed
 * to the engine yet. [toString] never carries the uri, a name or the bytes (they may end up in a log line).
 */
sealed interface IntakeRequest {
    val isPrivate: Boolean

    /** A `magnet:` link: tapped in a page, or handed over by another app (never private then). */
    class Magnet(val uri: String, override val isPrivate: Boolean) : IntakeRequest {
        override fun toString(): String = "Magnet(private=$isPrivate)"
    }

    /** The bytes of a .torrent file, already read and capped. */
    class TorrentBytes(val bytes: ByteArray, override val isPrivate: Boolean, val suggestedName: String? = null) : IntakeRequest {
        override fun toString(): String = "TorrentBytes(private=$isPrivate)"
    }

    /**
     * A web response that is a .torrent file; its body is not read yet. [download] forwards [IncomingDownload.discard]
     * at most once, so the reader, Cancel and a closing tab may all end it.
     */
    class TorrentResponse(download: IncomingDownload, override val isPrivate: Boolean) : IntakeRequest {
        val download: IncomingDownload = OnceDownload(download)

        override fun toString(): String = "TorrentResponse(private=$isPrivate)"
    }

    /** A `content:` .torrent from another app (VIEW or SEND), not read yet. Never private. */
    class TorrentUri(val uri: String, override val isPrivate: Boolean = false) : IntakeRequest {
        override fun toString(): String = "TorrentUri(private=$isPrivate)"
    }
}

/** Why an intake did not reach the sheet's Download button. Logged by name only. */
enum class IntakeRefusal { TOO_LARGE, BAD_MAGNET, NOT_CONTENT, OWN_PROVIDER, READ_FAILED, TIMEOUT }

/** The intake lines of the torrent log: kinds, enum names and booleans only. */
object IntakeLog {
    fun kind(request: IntakeRequest): String =
        when (request) {
            is IntakeRequest.Magnet -> "magnet"
            is IntakeRequest.TorrentResponse -> "response"
            is IntakeRequest.TorrentUri -> "uri"
            is IntakeRequest.TorrentBytes -> "bytes"
        }

    fun received(request: IntakeRequest): String = "intake ${kind(request)} ${request.isPrivate}"

    const val BUSY = "intake busy"
    const val ADDED = "intake added"
    const val CANCELLED = "intake cancelled"

    fun refused(reason: IntakeRefusal): String = "intake refused ${reason.name}"
}

/**
 * [discard] reaches the real download at most once, whoever calls it (the reader, Cancel, a closing tab), and
 * [openBody] at most once and never after a discard. A discard after the body was opened is forwarded too: it is a
 * no-op for the engine's downloads, and it makes sure a stream still being opened is not left behind.
 */
private class OnceDownload(private val inner: IncomingDownload) : IncomingDownload by inner {
    private val opened = AtomicBoolean(false)
    private val discarded = AtomicBoolean(false)

    override fun openBody() = if (!discarded.get() && opened.compareAndSet(false, true)) inner.openBody() else null

    override fun discard() {
        if (discarded.compareAndSet(false, true)) inner.discard()
    }
}

/** Which incoming things are torrents. Pure. */
object TorrentIntake {
    const val MIME = "application/x-bittorrent"

    /** Longest `content:` URI accepted from another app (as for shared documents). */
    private const val MAX_URI_LENGTH = 8_192
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val ACTION_SEND = "android.intent.action.SEND"

    /**
     * A response is a .torrent when its type says so, or when the name it would be saved under ends in `.torrent`
     * and its type is empty, `application/octet-stream` or the torrent type. HTML is never a torrent.
     */
    fun isTorrentResponse(contentType: String?, contentDisposition: String?, url: String): Boolean {
        val mime = if (contentType.isNullOrBlank()) "" else DownloadFileNames.cleanMime(contentType)
        if (mime == MIME) return true
        if (mime != "" && mime != DownloadFileNames.DEFAULT_MIME) return false
        val name = runCatching { DownloadFileNames.resolve(contentDisposition, url, null) { null } }.getOrNull() ?: return false
        return name.endsWith(".torrent", ignoreCase = true)
    }

    sealed interface Check {
        class Ok(val request: IntakeRequest) : Check

        class Refused(val reason: IntakeRefusal) : Check

        /** Not a torrent intent at all (another action or type): nothing to say. */
        object NotTorrent : Check
    }

    /**
     * A VIEW (`content:` data) or SEND (`content:` EXTRA_STREAM, [streamUri]) intent of type [MIME] from another app,
     * mirroring the shared-document rules: 8 KB cap, no whitespace or control characters, `content:` only (never
     * `file:`), and never one of this app's own providers ([ownerOfAuthority] as for [contentProviderIsThisApp]).
     */
    fun check(
        action: String?,
        dataString: String?,
        mimeType: String?,
        streamUri: String?,
        ourPackage: String,
        ownerOfAuthority: (String) -> String?,
    ): Check {
        if (mimeType.isNullOrBlank() || DownloadFileNames.cleanMime(mimeType) != MIME) return Check.NotTorrent
        val raw =
            when (action) {
                ACTION_VIEW -> dataString
                ACTION_SEND -> streamUri?.takeIf { it.isNotEmpty() }
                else -> return Check.NotTorrent
            } ?: return Check.Refused(IntakeRefusal.NOT_CONTENT)
        if (raw.length > MAX_URI_LENGTH) return Check.Refused(IntakeRefusal.NOT_CONTENT)
        if (raw.any { it.isWhitespace() || it.isISOControl() }) return Check.Refused(IntakeRefusal.NOT_CONTENT)
        if (schemeOf(raw) != "content") return Check.Refused(IntakeRefusal.NOT_CONTENT)
        if (contentProviderIsThisApp(raw, ourPackage, ownerOfAuthority)) return Check.Refused(IntakeRefusal.OWN_PROVIDER)
        return Check.Ok(IntakeRequest.TorrentUri(raw))
    }

    /** [check]'s request, or null. */
    fun intakeRequest(
        action: String?,
        dataString: String?,
        mimeType: String?,
        streamUri: String?,
        ourPackage: String,
        ownerOfAuthority: (String) -> String?,
    ): IntakeRequest? = (check(action, dataString, mimeType, streamUri, ourPackage, ownerOfAuthority) as? Check.Ok)?.request
}

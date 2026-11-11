package com.shibi.orbis.passwords

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import java.util.UUID

/** How long a copied secret may stay on the clipboard before Orbis clears it. */
const val SENSITIVE_CLIP_LIFETIME_MS = 60_000L

/** Label of a clip that holds a secret. */
const val SENSITIVE_CLIP_LABEL = "orbis-secret"

/** ClipDescription.EXTRA_IS_SENSITIVE (API 33); the same key string is honoured by Android 13+ only, harmless below. */
private const val IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"

/** Extra that marks a clip as the one Orbis placed; its value is the random token of [SensitiveClip]. */
internal const val SENSITIVE_CLIP_TOKEN_KEY = "com.shibi.orbis.extra.CLIP_TOKEN"

/** What to do about the secret Orbis copied, given what is on the clipboard now. */
internal enum class ClipVerdict {
    /** Nothing of ours is waiting. */
    NONE,

    /** Ours may still be there, but it is too early, Orbis is not in front, or the clipboard cannot be read now. */
    WAIT,

    /** The clipboard holds something else: the user replaced our clip. Never touch it. */
    FORGET,

    /** Our clip is still there and its time is up. */
    CLEAR,
}

/**
 * The decision behind [SensitiveClip.clearIfDue]. [clipToken] is the token found on the current primary clip, or null
 * when the clip carries none; [clipReadable] is false when no clip could be read at all (empty, or Android withheld
 * it because Orbis is not focused), which is never taken as "replaced".
 */
internal fun clipVerdict(
    pendingToken: String?,
    copiedAt: Long,
    now: Long,
    clipReadable: Boolean,
    clipToken: String?,
    foreground: Boolean,
): ClipVerdict =
    when {
        pendingToken == null -> ClipVerdict.NONE
        !clipReadable -> ClipVerdict.WAIT
        clipToken != pendingToken -> ClipVerdict.FORGET
        !foreground -> ClipVerdict.WAIT
        now - copiedAt < SENSITIVE_CLIP_LIFETIME_MS -> ClipVerdict.WAIT
        else -> ClipVerdict.CLEAR
    }

/**
 * The secret (a password) Orbis last put on the clipboard, in memory only. Owned by the app container. A secret is
 * copied with a random token in the clip's extras; [SENSITIVE_CLIP_LIFETIME_MS] later, and again whenever
 * MainActivity comes to the front, the clip is cleared if Orbis is in front and the clip still carries that token.
 * Android lets only the focused app read the clipboard, so the check cannot run in the background; a clip the user
 * has since replaced is never touched.
 */
class SensitiveClip(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit = { delayMs, task ->
        Handler(Looper.getMainLooper()).postDelayed(task, delayMs)
    },
) {
    private class Pending(val token: String, val copiedAt: Long)

    private var pending: Pending? = null

    /** True while MainActivity has window focus, the only time the clipboard can be read. Set by MainActivity. */
    @Volatile var foreground: Boolean = false

    /** Puts [text] on the clipboard as a secret and schedules its clearing. */
    fun copy(clipboard: ClipboardManager, text: String) {
        val token = newToken()
        val clip = ClipData.newPlainText(SENSITIVE_CLIP_LABEL, text)
        clip.description.extras =
            PersistableBundle().apply {
                putBoolean(IS_SENSITIVE_KEY, true)
                putString(SENSITIVE_CLIP_TOKEN_KEY, token)
            }
        clipboard.setPrimaryClip(clip)
        synchronized(this) { pending = Pending(token, now()) }
        // A little after the lifetime, so the check never runs a moment too early.
        schedule(SENSITIVE_CLIP_LIFETIME_MS + 500L) { clearIfDue(clipboard) }
    }

    /** Clears the clipboard if it still holds our secret and its time is up; see [clipVerdict]. */
    fun clearIfDue(clipboard: ClipboardManager) {
        val current = synchronized(this) { pending } ?: return
        val description = runCatching { if (clipboard.hasPrimaryClip()) clipboard.primaryClipDescription else null }.getOrNull()
        val verdict =
            clipVerdict(
                pendingToken = current.token,
                copiedAt = current.copiedAt,
                now = now(),
                clipReadable = description != null,
                clipToken = description?.extras?.getString(SENSITIVE_CLIP_TOKEN_KEY),
                foreground = foreground,
            )
        when (verdict) {
            ClipVerdict.NONE, ClipVerdict.WAIT -> Unit
            ClipVerdict.FORGET -> synchronized(this) { if (pending === current) pending = null }
            ClipVerdict.CLEAR -> {
                // Kept when the clear failed, so the next check tries again.
                if (runCatching { clipboard.clearPrimaryClip() }.isSuccess) {
                    synchronized(this) { if (pending === current) pending = null }
                }
            }
        }
    }
}

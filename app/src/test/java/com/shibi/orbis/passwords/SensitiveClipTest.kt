package com.shibi.orbis.passwords

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The clipboard clear for copied passwords: only our clip, only after a minute, only while Orbis is in front. */
class ClipVerdictTest {
    private fun verdict(
        pending: Boolean = true,
        copiedAt: Long = 0L,
        now: Long = SENSITIVE_CLIP_LIFETIME_MS,
        readable: Boolean = true,
        ours: Boolean = true,
        foreground: Boolean = true,
    ) = clipVerdict(pending, copiedAt, now, readable, ours, foreground)

    @Test
    fun clearsOurClipOnceItsTimeIsUp() {
        assertEquals(ClipVerdict.CLEAR, verdict())
        assertEquals(ClipVerdict.CLEAR, verdict(now = SENSITIVE_CLIP_LIFETIME_MS * 10))
    }

    @Test
    fun waitsBeforeTheMinuteIsUp() {
        assertEquals(ClipVerdict.WAIT, verdict(now = SENSITIVE_CLIP_LIFETIME_MS - 1))
        assertEquals(ClipVerdict.WAIT, verdict(copiedAt = 5_000L, now = SENSITIVE_CLIP_LIFETIME_MS))
    }

    @Test
    fun neverClearsAClipTheUserReplaced() {
        assertEquals(ClipVerdict.FORGET, verdict(ours = false))
        assertEquals("replaced even before the minute", ClipVerdict.FORGET, verdict(now = 1L, ours = false))
    }

    @Test
    fun waitsWhileInTheBackgroundOrTheClipboardIsUnreadable() {
        assertEquals(ClipVerdict.WAIT, verdict(foreground = false))
        assertEquals("unreadable is not replaced", ClipVerdict.WAIT, verdict(readable = false, ours = false))
    }

    @Test
    fun nothingPendingDoesNothing() {
        assertEquals(ClipVerdict.NONE, verdict(pending = false))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SensitiveClipTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val clipboard = app.getSystemService(ClipboardManager::class.java)
    private var clock = 1_000L
    private val scheduled = ArrayList<Pair<Long, () -> Unit>>()
    private var tokens = 0
    private val secrets =
        SensitiveClip(now = { clock }, newToken = { "token-${++tokens}" }, schedule = { d, t -> scheduled += d to t }).also {
            it.foreground = true
        }

    private fun clipText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun copiedSecretIsLabelledMarkedAndScheduled() {
        secrets.copy(clipboard, "hunter2")
        assertEquals("hunter2", clipText())
        val description = clipboard.primaryClipDescription!!
        assertEquals(SENSITIVE_CLIP_LABEL, description.label)
        assertTrue(description.extras.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertEquals("token-1", description.extras.getString(SENSITIVE_CLIP_TOKEN_KEY))
        assertEquals(1, scheduled.size)
        assertTrue(scheduled.single().first >= SENSITIVE_CLIP_LIFETIME_MS)
    }

    @Test
    fun clearsAfterAMinuteWhenStillOurs() {
        secrets.copy(clipboard, "hunter2")
        clock += SENSITIVE_CLIP_LIFETIME_MS - 1
        secrets.clearIfDue(clipboard)
        assertEquals("too early", "hunter2", clipText())
        clock += 1
        scheduled.single().second()
        assertFalse(clipboard.hasPrimaryClip() && clipText() == "hunter2")
    }

    @Test
    fun aReplacedClipIsNeverTouched() {
        secrets.copy(clipboard, "hunter2")
        clipboard.setPrimaryClip(ClipData.newPlainText("other app", "shopping list"))
        clock += SENSITIVE_CLIP_LIFETIME_MS * 2
        secrets.clearIfDue(clipboard)
        assertEquals("shopping list", clipText())
        // Forgotten: even if the same text is copied back by someone else later, it is not ours.
        clipboard.setPrimaryClip(ClipData.newPlainText("orbis-secret", "hunter2"))
        secrets.clearIfDue(clipboard)
        assertEquals("hunter2", clipText())
    }

    /**
     * The emulator's host clipboard sync puts back a copy of the clip within milliseconds: same text, label "host
     * clipboard", its own extras, no token (seen on API 36, H-D20). It still holds the secret, so it is still cleared.
     */
    @Test
    fun aSystemCopyOfTheSecretWithoutTheTokenIsStillCleared() {
        secrets.copy(clipboard, "hunter2")
        val echo = ClipData.newPlainText("host clipboard", "hunter2")
        echo.description.extras = android.os.PersistableBundle().apply { putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true) }
        clipboard.setPrimaryClip(echo)
        secrets.clearIfDue(clipboard)
        assertEquals("not forgotten, and too early", "hunter2", clipText())
        clock += SENSITIVE_CLIP_LIFETIME_MS
        scheduled.single().second()
        assertFalse(clipboard.hasPrimaryClip() && clipText() == "hunter2")
    }

    @Test
    fun aClipWithOtherTextAndNoTokenIsNeverTouched() {
        secrets.copy(clipboard, "hunter2")
        clipboard.setPrimaryClip(ClipData.newPlainText("host clipboard", "hunter3"))
        clock += SENSITIVE_CLIP_LIFETIME_MS
        secrets.clearIfDue(clipboard)
        assertEquals("hunter3", clipText())
    }

    @Test
    fun inTheBackgroundItWaitsAndClearsOnReturn() {
        secrets.copy(clipboard, "hunter2")
        secrets.foreground = false
        clock += SENSITIVE_CLIP_LIFETIME_MS + 1
        scheduled.single().second()
        assertEquals("hunter2", clipText())
        secrets.foreground = true // MainActivity resumed and has focus
        secrets.clearIfDue(clipboard)
        assertFalse(clipboard.hasPrimaryClip() && clipText() == "hunter2")
    }

    @Test
    fun aNewerCopyRestartsTheMinute() {
        secrets.copy(clipboard, "first")
        clock += 50_000L
        secrets.copy(clipboard, "second")
        clock += 20_000L
        secrets.clearIfDue(clipboard)
        assertEquals("second", clipText())
        clock += 40_000L
        secrets.clearIfDue(clipboard)
        assertFalse(clipboard.hasPrimaryClip() && clipText() == "second")
    }
}

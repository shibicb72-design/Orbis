package com.shibi.orbis.engine

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EXTRA_STREAM is an untrusted Parcelable. The untyped getter crashes on a foreign class below API 33, and the
 * API 33 typed getter is buggy, so both API levels go through IntentCompat inside runCatching.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33], application = android.app.Application::class)
class SharedDocumentIntentTest {
    private val document = Uri.parse("content://com.whatsapp.provider.media/item/1")

    @Test
    fun aStreamUriIsReadBack() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, document)
        assertEquals(document, streamUriFrom(intent))
    }

    @Test
    fun aForeignParcelableDoesNotCrashAndAStringExtraDoesNotCrash() {
        val wrongType = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Intent("not a stream"))
        assertNull(streamUriFrom(wrongType))
        val text = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "not a uri")
        assertNull(streamUriFrom(text))
    }

    @Test
    fun clipDataIsReadWhenTheExtraIsMissingOrTheWrongType() {
        val clipOnly = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("doc", document)
        }
        assertEquals(document, streamUriFrom(clipOnly))

        val wrongExtraAndClip = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Intent("not a stream"))
            clipData = ClipData.newRawUri("doc", document)
        }
        assertEquals(document, streamUriFrom(wrongExtraAndClip))
    }

    @Test
    fun aClipWithoutAUriDoesNotCrash() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newPlainText("note", "hello")
        }
        assertNull(streamUriFrom(intent))
        assertNull(streamUriFrom(Intent(Intent.ACTION_SEND)))
    }
}

package com.shibi.orbis.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShareIntentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val authority get() = "${context.packageName}.downloads"
    private val id = "11111111-1111-1111-1111-111111111111"

    // The FileProvider is the one the merged manifest declares, reading the real res/xml/download_paths.xml.

    /**
     * androidx FileProvider matches roots with a hard-coded '/', so on a Windows host no root ever matches and every
     * file is refused. CI (ubuntu) runs these; a Windows run skips them instead of failing.
     */
    private fun assumeUnixPaths() = assumeTrue("FileProvider root matching needs '/' paths", File.separatorChar == '/')

    private fun file(base: File, vararg parts: String): File =
        File(base, parts.joinToString(File.separator)).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(37, 80, 68, 70))
        }

    private fun inner(chooser: Intent): Intent {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        return chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
    }

    @Test
    fun theChooserSendsOneReadOnlyContentUri() {
        assumeUnixPaths()
        val f = file(context.cacheDir, "shared_out", "n", id, "Report.pdf")
        val chooser = ShareIntents.fileChooser(context, f, "application/pdf", "Share file")
        assertNotNull(chooser)
        val send = inner(chooser!!)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content://$authority/shared/$id/Report.pdf", stream.toString())
        assertEquals(1, send.clipData!!.itemCount)
        assertEquals(stream, send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
        assertFalse(send.hasExtra(Intent.EXTRA_SUBJECT))
        assertNull(send.data)
        for (intent in listOf(chooser, send)) {
            val extras = intent.extras
            for (key in extras?.keySet().orEmpty()) {
                @Suppress("DEPRECATION")
                val value = extras!!.get(key)
                assertFalse("$key carries a file: value", value.toString().startsWith("file:"))
            }
            assertFalse(intent.dataString.orEmpty().startsWith("file:"))
        }
    }

    @Test
    fun orbisItselfIsNotOffered() {
        assumeUnixPaths()
        val f = file(context.cacheDir, "shared_out", "n", id, "a.pdf")
        val chooser = ShareIntents.fileChooser(context, f, "application/pdf", "Share file")!!
        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.toList()
        assertEquals(listOf(ComponentName(context, MainActivity::class.java)), excluded)
    }

    @Test
    fun aPrivateShareUsesTheSecondRoot() {
        assumeUnixPaths()
        val f = file(context.cacheDir, "shared_out", "p", id, "a.html")
        val send = inner(ShareIntents.fileChooser(context, f, "text/html", "Share file")!!)
        assertEquals("text/html", send.type)
        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content://$authority/shared2/$id/a.html", stream.toString())
    }

    @Test
    fun filesOutsideTheShareRootsAreRefused() {
        val refused =
            listOf(
                file(context.cacheDir, "imports", id, "a.pdf"),
                file(context.cacheDir, "import-staging", id, "a.pdf"),
                file(context.cacheDir, "uploads", "a.pdf"),
                file(context.cacheDir, "shared_out", "a.pdf"),
                file(context.filesDir, "downloads_index.json"),
                file(context.filesDir, "a.pdf"),
            )
        for (f in refused) assertNull(f.path, ShareIntents.fileChooser(context, f, "application/pdf", "Share file"))
    }
}

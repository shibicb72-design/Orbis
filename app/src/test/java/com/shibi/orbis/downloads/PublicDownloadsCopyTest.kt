package com.shibi.orbis.downloads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PublicDownloadsCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aCopyReturnsItsContentUri() {
        val file = File(tmp.root, "a.txt").apply { writeText("abc") }
        val uri = copyToPublicDownloads(context, file, "a.txt", "text/plain", "Orbis/Sintel")
        assertNotNull(uri)
    }

    @Test
    fun aMissingFileGivesNoUri() {
        assertNull(copyToPublicDownloads(context, File(tmp.root, "missing"), "a.txt", "text/plain"))
    }
}

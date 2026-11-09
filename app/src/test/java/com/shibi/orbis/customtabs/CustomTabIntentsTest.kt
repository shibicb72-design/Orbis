package com.shibi.orbis.customtabs

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CustomTabIntentsTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun contentColorKeepsContrast() {
        assertEquals(white, contentColorOn(0xFF1565C0.toInt())) // dark blue toolbar
        assertEquals(black, contentColorOn(0xFFFFEB3B.toInt())) // yellow toolbar
        assertEquals(white, contentColorOn(black))
        assertEquals(black, contentColorOn(white))
    }

    @Test
    fun recognisesCustomTabRequestsOnlyWithTheSessionExtra() {
        val plain = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
        assertFalse(isCustomTabRequest(plain))
        val ct = Intent(plain).putExtra(EXTRA_CUSTOM_TABS_SESSION, true)
        assertTrue(isCustomTabRequest(ct))
    }

    @Test
    fun readsToolbarColorAndTitleFlagOnly() {
        val ct =
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
                .putExtra(EXTRA_CUSTOM_TABS_SESSION, true)
                .putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF112233.toInt())
                .putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
        assertEquals(CustomTabLook(0xFF112233.toInt(), true), customTabLookOf(ct))
        val bare = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/")).putExtra(EXTRA_CUSTOM_TABS_SESSION, true)
        assertNull(customTabLookOf(bare).toolbarColor)
        assertFalse(customTabLookOf(bare).showTitle)
    }
}

package com.shibi.orbis.ui.home

import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dashboard Reset dialog says what Reset deletes: the counts, the YouTube estimate and the memory history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StatsResetTextTest {
    @Test
    fun theResetDialogNamesTheMemoryHistory() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(
            "The counts start again from zero, including ad breaks avoided on YouTube, and the memory history is deleted. " +
                "Nothing else is deleted.",
            context.getString(R.string.stats_reset_body),
        )
        assertEquals("Reset privacy stats?", context.getString(R.string.stats_reset_title))
    }
}

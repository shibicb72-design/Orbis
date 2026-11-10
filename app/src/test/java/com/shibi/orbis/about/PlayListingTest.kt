package com.shibi.orbis.about

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlayListingTest {
    @Test
    fun listingTargetsThePlayPackageByMarketThenHttps() {
        val intents = playListingIntents()
        assertEquals(2, intents.size)
        intents.forEach { intent ->
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        assertEquals("market://details?id=com.shibi.orbis", intents[0].dataString)
        assertEquals("https://play.google.com/store/apps/details?id=com.shibi.orbis", intents[1].dataString)
    }

    @Test
    fun marketOpensAndTheWebPageIsNotTried() {
        val started = mutableListOf<String>()
        val opened =
            openFirst(playListingIntents()) { intent ->
                started.add(intent.dataString!!)
                true
            }
        assertTrue(opened)
        assertEquals(listOf("market://details?id=com.shibi.orbis"), started)
    }

    @Test
    fun httpsPageIsUsedWhenNothingHandlesMarket() {
        val started = mutableListOf<String>()
        val opened =
            openFirst(playListingIntents()) { intent ->
                started.add(intent.dataString!!)
                intent.data?.scheme == "https"
            }
        assertTrue(opened)
        assertEquals(
            listOf(
                "market://details?id=com.shibi.orbis",
                "https://play.google.com/store/apps/details?id=com.shibi.orbis",
            ),
            started,
        )
    }

    @Test
    fun neitherListingLeavesTheClickUnopened() {
        assertFalse(openFirst(playListingIntents()) { false })
    }
}

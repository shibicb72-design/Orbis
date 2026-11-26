package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.ContentBlocking.AntiTracking

/** The tracker classes handed to the engine, by Settings > Also block content trackers (etp21-2). */
class AntiTrackingClassesTest {
    private fun has(mask: Int, cls: Int) = mask and cls == cls

    @Test
    fun settingOffBlocksEveryStrictClassButContent() {
        val mask = antiTrackingClasses(blockContentTrackers = false)
        assertTrue("advert", has(mask, AntiTracking.AD))
        assertTrue("analytics", has(mask, AntiTracking.ANALYTIC))
        assertTrue("social", has(mask, AntiTracking.SOCIAL))
        assertTrue("test", has(mask, AntiTracking.TEST))
        assertTrue("cryptomining", has(mask, AntiTracking.CRYPTOMINING))
        assertTrue("fingerprinting", has(mask, AntiTracking.FINGERPRINTING))
        assertTrue("e-mail", has(mask, AntiTracking.EMAIL))
        assertEquals("content is out", 0, mask and AntiTracking.CONTENT)
        // Nothing else is dropped or added: a class an engine update adds to STRICT is kept.
        assertEquals(AntiTracking.STRICT, mask or AntiTracking.CONTENT)
    }

    @Test
    fun settingOnIsExactlyTheEnginesStrict() {
        val mask = antiTrackingClasses(blockContentTrackers = true)
        assertEquals(AntiTracking.STRICT, mask)
        assertTrue("content is in", has(mask, AntiTracking.CONTENT))
    }

    @Test
    fun theStartUpLineNamesWhatIsOn() {
        assertEquals("STRICT-minus-content", antiTrackingLogName(blockContentTrackers = false))
        assertEquals("STRICT", antiTrackingLogName(blockContentTrackers = true))
    }
}

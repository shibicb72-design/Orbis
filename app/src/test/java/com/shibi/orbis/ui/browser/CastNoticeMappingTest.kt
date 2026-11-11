package com.shibi.orbis.ui.browser

import com.shibi.orbis.R
import com.shibi.orbis.cast.CastNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S-01: a changed TV certificate gets its own notice, and only that notice offers "Forget this TV". */
class CastNoticeMappingTest {
    @Test
    fun untrustedHasItsOwnTextAndOffersForget() {
        assertEquals(R.string.cast_untrusted, castNoticeText(CastNotice.UNTRUSTED))
        assertTrue(castNoticeOffersForget(CastNotice.UNTRUSTED))
    }

    @Test
    fun noOtherNoticeOffersForgetAndEachHasDistinctText() {
        CastNotice.entries.filter { it != CastNotice.UNTRUSTED }.forEach { assertFalse(it.name, castNoticeOffersForget(it)) }
        assertEquals(CastNotice.entries.size, CastNotice.entries.map(::castNoticeText).toSet().size)
    }
}

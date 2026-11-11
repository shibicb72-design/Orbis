package com.shibi.orbis.ui.browser

import com.shibi.orbis.R
import com.shibi.orbis.cast.CastNotice
import com.shibi.orbis.cast.CastTrustReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F-01: the trust question is drawn by CastTrustQuestion with one text per reason; every other notice has its own text. */
class CastNoticeMappingTest {
    @Test
    fun confirmDeviceHasNoPlainNoticeTextAndEachReasonHasItsOwn() {
        assertNull(castNoticeText(CastNotice.CONFIRM_DEVICE))
        assertEquals(R.string.cast_trust_first, castTrustText(CastTrustReason.FIRST_USE))
        assertEquals(R.string.cast_trust_changed, castTrustText(CastTrustReason.KEY_CHANGED))
        assertEquals(R.string.cast_trust_renamed, castTrustText(CastTrustReason.RENAMED))
    }

    @Test
    fun everyOtherNoticeHasDistinctText() {
        val others = CastNotice.entries.filter { it != CastNotice.CONFIRM_DEVICE }
        val texts = others.map { castNoticeText(it) }
        texts.forEachIndexed { i, text -> assertEquals(others[i].name, false, text == null) }
        assertEquals(others.size, texts.toSet().size)
    }
}

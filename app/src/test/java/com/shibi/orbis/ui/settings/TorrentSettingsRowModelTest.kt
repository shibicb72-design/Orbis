package com.shibi.orbis.ui.settings

import com.shibi.orbis.torrent.TorrentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSettingsRowModelTest {
    @Test
    fun defaultsSayNoLimitAndHideTheRatioRow() {
        val m = TorrentSettingsRowModel.from(TorrentSettings())
        assertEquals(LimitLabel.NoLimit, m.down)
        assertEquals(LimitLabel.NoLimit, m.up)
        assertFalse(m.ratioRowVisible)
        assertNull(m.ratio)
    }

    @Test
    fun capsShowInKilobytesAndTheRatioWithOneDecimal() {
        val m = TorrentSettingsRowModel.from(TorrentSettings(stopSeeding = false, downBps = 512_000, upBps = 999, seedRatioLimit = 1.5f))
        assertEquals(LimitLabel.Kbps(512), m.down)
        // Under 1 KB/s is still a limit, never "0 KB/s".
        assertEquals(LimitLabel.Kbps(1), m.up)
        assertTrue(m.ratioRowVisible)
        assertEquals("1.5", m.ratio)
        assertEquals("100.0", TorrentSettingsRowModel.formatRatio(100f))
    }

    @Test
    fun theDialogFieldsAcceptOnlyTheirRange() {
        assertEquals(0, TorrentSettingsRowModel.parseKbps(""))
        assertEquals(0, TorrentSettingsRowModel.parseKbps("0"))
        assertEquals(512, TorrentSettingsRowModel.parseKbps(" 512 "))
        assertEquals(1_000_000, TorrentSettingsRowModel.parseKbps("1000000"))
        assertNull(TorrentSettingsRowModel.parseKbps("1000001"))
        assertNull(TorrentSettingsRowModel.parseKbps("-1"))
        assertNull(TorrentSettingsRowModel.parseKbps("5.5"))
        assertNull(TorrentSettingsRowModel.parseKbps("99999999999"))

        assertEquals(0.1f, TorrentSettingsRowModel.parseRatio("0.1"))
        assertEquals(1.5f, TorrentSettingsRowModel.parseRatio("1,5"))
        assertEquals(100f, TorrentSettingsRowModel.parseRatio("100"))
        assertEquals(2f, TorrentSettingsRowModel.parseRatio("2"))
        assertNull(TorrentSettingsRowModel.parseRatio("0"))
        assertNull(TorrentSettingsRowModel.parseRatio("0.05"))
        assertNull(TorrentSettingsRowModel.parseRatio("100.1"))
        assertNull(TorrentSettingsRowModel.parseRatio("1.25"))
        assertNull(TorrentSettingsRowModel.parseRatio(""))
        assertNull(TorrentSettingsRowModel.parseRatio("abc"))
    }
}

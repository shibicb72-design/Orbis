package com.shibi.orbis.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Category bits as libtorrent 2.0 defines them (include/libtorrent/alert.hpp). */
class AlertMaskTest {
    private fun bit(n: Int) = 1 shl n

    private val all = -1 // alert_category_t::all(): every bit
    private val ipBlock = bit(8)
    private val logs = listOf(bit(13), bit(14), bit(15), bit(17), bit(19), bit(20)) // session, torrent, peer, dht, port mapping, picker

    private val mask = alertMask(all, ipBlock, logs)

    @Test
    fun ipBlockAndTheLogsAreOff() {
        assertEquals(0, mask and ipBlock)
        for (c in logs) assertEquals(0, mask and c)
    }

    @Test
    fun itIsLibtorrent4jsOwnMaskWithoutIpBlock() {
        // SessionManager.alertMask(false): all categories minus the six logs.
        val libtorrent4j = logs.fold(all) { m, c -> m and c.inv() }
        assertTrue(libtorrent4j and ipBlock != 0)
        assertEquals(libtorrent4j and ipBlock.inv(), mask)
    }

    @Test
    fun everyCategoryTheListenerNeedsStaysOn() {
        // error, peer, port mapping, storage (save_resume_data, file_error), tracker, connect, status (state_update,
        // metadata_received, torrent_finished), dht.
        for (n in listOf(0, 1, 2, 3, 4, 5, 6, 10)) assertTrue("bit $n", mask and bit(n) != 0)
        assertFalse(mask == 0)
    }
}

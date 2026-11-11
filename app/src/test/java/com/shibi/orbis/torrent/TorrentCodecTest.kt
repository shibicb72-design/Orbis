package com.shibi.orbis.torrent

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentCodecTest {
    private val id = "0123456789abcdef0123456789abcdef"

    private fun entry(
        id: String = this.id,
        status: TorrentStatus = TorrentStatus.DOWNLOADING,
        isPrivate: Boolean = false,
    ) = TorrentEntry(
        id = id,
        displayName = "Sintel",
        totalBytes = 100,
        doneBytes = 40,
        status = status,
        addedAt = 1234,
        saveDir = "/data/torrents/$id",
        publishedPath = null,
        isPrivate = isPrivate,
        errorKind = null,
        hasMetadata = true,
        downBps = 999,
        peers = 7,
    )

    @Test
    fun roundTripKeepsPersistedFieldsAndDropsLiveOnes() {
        val e =
            entry().copy(
                status = TorrentStatus.FAILED,
                errorKind = TorrentErrorKind.NO_SPACE,
                pauseReason = PauseReason.USER,
                publishedPath = "Download/Orbis/Sintel",
            )
        val back = TorrentCodec.decode(TorrentCodec.encode(listOf(e))).single()
        assertEquals(e.copy(downBps = 0, peers = 0), back)
    }

    @Test
    fun formatIsVersionedWithTheSpecFieldNames() {
        val o = JSONObject(TorrentCodec.encode(listOf(entry())))
        assertEquals(1, o.getInt("version"))
        val e = o.getJSONArray("entries").getJSONObject(0)
        for (k in listOf("id", "name", "total", "done", "status", "added", "saveDir")) assertTrue(k, e.has(k))
        assertFalse(e.has("published"))
        assertFalse(e.has("error"))
        assertFalse(e.has("downBps"))
        assertFalse(e.has("peers"))
    }

    @Test
    fun privateAndRemovedEntriesAreNeverWritten() {
        val text =
            TorrentCodec.encode(
                listOf(
                    entry(isPrivate = true),
                    entry(id = "1".repeat(32), status = TorrentStatus.REMOVED),
                    entry(id = "2".repeat(32)),
                ),
            )
        val back = TorrentCodec.decode(text)
        assertEquals(listOf("2".repeat(32)), back.map { it.id })
        assertFalse(text.contains(id))
    }

    @Test
    fun brokenEntriesAreSkipped() {
        val text =
            """{"version":1,"entries":[
              {"id":"not-an-id","name":"a","status":"DOWNLOADING","saveDir":"/x"},
              {"id":"$id","name":"a","status":"NO_SUCH_STATUS","saveDir":"/x"},
              {"id":"$id","name":"","status":"PAUSED","saveDir":"/x"},
              {"id":"$id","name":"a","status":"PAUSED"},
              {"id":"$id","name":"ok","status":"PAUSED","saveDir":"/x","error":"WHAT","pause":"QUEUED"},
              7
            ]}"""
        val e = TorrentCodec.decode(text).single()
        assertEquals("ok", e.displayName)
        assertNull(e.errorKind)
        assertEquals(PauseReason.QUEUED, e.pauseReason)
        assertEquals(-1L, e.totalBytes)
    }

    @Test(expected = JSONException::class)
    fun notAnIndexThrowsSoTheStoreCanSetItAside() {
        TorrentCodec.decode("{ broken")
    }

    @Test(expected = JSONException::class)
    fun missingEntriesArrayThrows() {
        TorrentCodec.decode("""{"version":1}""")
    }
}

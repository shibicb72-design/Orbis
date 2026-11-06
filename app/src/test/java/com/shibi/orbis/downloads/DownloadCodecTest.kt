package com.shibi.orbis.downloads

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadCodecTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(
        id: Long = 1,
        name: String = "a.txt",
        host: String? = "example.com",
        status: DownloadStatus = DownloadStatus.COMPLETED,
        path: String? = "/data/a.txt",
    ) = DownloadEntry(id, name, "text/plain", host, 10, 10, status, path, 1_700_000_000_000L + id)

    @Test
    fun roundTripKeepsEveryField() {
        val list =
            listOf(
                entry(1),
                entry(2, name = "b é日.bin", host = null, status = DownloadStatus.FAILED, path = null),
                entry(3, status = DownloadStatus.IN_PROGRESS).copy(totalBytes = -1, receivedBytes = 0),
                entry(4, status = DownloadStatus.CANCELLED, path = null),
            )
        assertEquals(list, DownloadCodec.decode(DownloadCodec.encode(list)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList<DownloadEntry>(), DownloadCodec.decode(DownloadCodec.encode(emptyList())))
    }

    @Test
    fun nullOrBlankOrGarbageDecodesToEmpty() {
        assertEquals(emptyList<DownloadEntry>(), DownloadCodec.decode(null))
        assertEquals(emptyList<DownloadEntry>(), DownloadCodec.decode(""))
        assertEquals(emptyList<DownloadEntry>(), DownloadCodec.decode("   "))
        assertEquals(emptyList<DownloadEntry>(), DownloadCodec.decode("not json"))
        assertEquals(emptyList<DownloadEntry>(), DownloadCodec.decode("[1,2,3]"))
        assertEquals(emptyList<DownloadEntry>(), DownloadCodec.decode("{\"entries\": 5}"))
        assertEquals(emptyList<DownloadEntry>(), DownloadCodec.decode("{\"version\":1"))
    }

    @Test
    fun truncatedFileDecodesToEmpty() {
        val full = DownloadCodec.encode(listOf(entry(1), entry(2)))
        assertEquals(emptyList<DownloadEntry>(), DownloadCodec.decode(full.substring(0, full.length / 2)))
    }

    @Test
    fun badEntriesAreSkippedGoodOnesKept() {
        val goodObject =
            org.json.JSONObject(DownloadCodec.encode(listOf(entry(7)))).getJSONArray("entries").getJSONObject(0).toString()
        val text =
            "{\"version\":1,\"entries\":[" +
                "5, null, {}, {\"id\":1,\"name\":\"x\",\"status\":\"WHAT\"}, " +
                "{\"id\":2,\"name\":\"\",\"status\":\"COMPLETED\"}, " +
                "{\"id\":\"zzz\",\"name\":\"x\",\"status\":\"COMPLETED\"}, " +
                goodObject + "]}"
        assertEquals(listOf(entry(7)), DownloadCodec.decode(text))
    }

    @Test
    fun missingOptionalFieldsGetSafeDefaults() {
        val decoded =
            DownloadCodec.decode("{\"entries\":[{\"id\":9,\"name\":\"x.bin\",\"status\":\"FAILED\"}]}").single()
        assertEquals(DownloadFileNames.DEFAULT_MIME, decoded.mimeType)
        assertEquals(-1L, decoded.totalBytes)
        assertEquals(0L, decoded.receivedBytes)
        assertNull(decoded.sourceHost)
        assertNull(decoded.filePath)
    }

    @Test
    fun negativeCountsAreClamped() {
        val decoded =
            DownloadCodec.decode(
                "{\"entries\":[{\"id\":9,\"name\":\"x\",\"status\":\"FAILED\",\"total\":-99,\"received\":-5}]}",
            ).single()
        assertEquals(-1L, decoded.totalBytes)
        assertEquals(0L, decoded.receivedBytes)
    }

    @Test
    fun encodedIndexHoldsNoUrl() {
        val text = DownloadCodec.encode(listOf(entry(1, host = "example.com")))
        assertFalse(text.contains("://"))
        assertTrue(text.contains("example.com"))
    }

    // ---- store ----

    @Test
    fun storeSaveThenLoad() {
        val store = DownloadStore(File(tmp.root, "idx.json"))
        val list = listOf(entry(1), entry(2))
        store.save(list)
        assertEquals(list, store.load())
    }

    @Test
    fun storeMissingFileLoadsEmpty() {
        assertEquals(emptyList<DownloadEntry>(), DownloadStore(File(tmp.root, "nope.json")).load())
    }

    @Test
    fun storeCorruptFileLoadsEmptyAndCanBeOverwritten() {
        val f = File(tmp.root, "idx.json")
        f.writeText("{{{ garbage")
        val store = DownloadStore(f)
        assertEquals(emptyList<DownloadEntry>(), store.load())
        store.save(listOf(entry(3)))
        assertEquals(listOf(entry(3)), store.load())
    }

    @Test
    fun storeSaveReplacesAndLeavesNoTempFile() {
        val f = File(tmp.root, "idx.json")
        val store = DownloadStore(f)
        store.save(listOf(entry(1)))
        store.save(listOf(entry(2)))
        assertEquals(listOf(entry(2)), store.load())
        assertEquals(listOf("idx.json"), tmp.root.list()!!.toList())
    }

    @Test
    fun storeCreatesMissingParentDirectory() {
        val store = DownloadStore(File(tmp.root, "sub/dir/idx.json"))
        store.save(listOf(entry(1)))
        assertEquals(listOf(entry(1)), store.load())
    }
}

package com.shibi.orbis.history

import com.shibi.orbis.storage.AtomicTextFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryCodecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val a = HistoryEntry("https://a.example/", "A", 1_000)
    private val b = HistoryEntry("https://b.example/?q=1&r=2", "B é😀", 2_000)

    @Test
    fun roundTrips() {
        assertEquals(listOf(a, b), HistoryCodec.decode(HistoryCodec.encode(listOf(a, b))))
        assertEquals(emptyList<HistoryEntry>(), HistoryCodec.decode(HistoryCodec.encode(emptyList())))
    }

    @Test
    fun wholeDocumentFailureThrows() {
        listOf("", "nope", "[]", "{}", "{\"history\":\"x\"}", "{\"history\":[").forEach {
            try {
                HistoryCodec.decode(it)
                fail("expected failure for: $it")
            } catch (e: Exception) {
                // expected
            }
        }
    }

    @Test
    fun badEntriesAndRepeatedUrlsAreSkipped() {
        val out = HistoryCodec.decode(
            """{"history":[
                5,
                {"url":"https://one.example/","title":"newest","visited":30},
                {"url":"javascript:alert(1)","title":"x","visited":29},
                {"url":"https://user@evil.example/","title":"x","visited":28},
                {"url":"https://one.example/","title":"older repeat","visited":27},
                {"title":"no url"},
                {"url":null},
                {"url":"https://two.example/","title":null,"visited":-4}
            ]}""",
        )
        assertEquals(listOf("https://one.example/", "https://two.example/"), out.map { it.url })
        assertEquals("newest", out[0].title)
        assertEquals("", out[1].title)
        assertEquals(0L, out[1].visitedAt)
    }

    @Test
    fun titlesAreCleanedAndTheListIsCapped() {
        val sb = StringBuilder("""{"history":[{"url":"https://t.example/","title":"  a\n\tb\u202E  "}""")
        for (i in 1..(MAX_HISTORY_ENTRIES + 10)) sb.append(""",{"url":"https://h$i.example/","title":"t","visited":$i}""")
        sb.append("]}")
        val out = HistoryCodec.decode(sb.toString())
        assertEquals("a b", out[0].title)
        assertEquals(MAX_HISTORY_ENTRIES, out.size)
    }

    @Test
    fun corruptFileIsSetAsideAndNeverOverwritten() {
        val file = File(tmp.root, "history/history.json")
        file.parentFile!!.mkdirs()
        file.writeText("garbage{")
        val store = FileHistoryStore(AtomicTextFile(file))
        assertEquals(emptyList<HistoryEntry>(), store.load())
        val corrupt = File(file.parentFile, "history.json.corrupt")
        assertEquals("garbage{", corrupt.readText())
        assertFalse(file.exists())
        store.save(listOf(a))
        assertEquals(listOf(a), FileHistoryStore(AtomicTextFile(file)).load())
        assertEquals("garbage{", corrupt.readText())
    }

    @Test
    fun clearingDeletesTheFileThatWasSetAside() {
        val file = File(tmp.root, "history/history.json")
        file.parentFile!!.mkdirs()
        file.writeText("garbage{ https://secret.example/")
        val store = FileHistoryStore(AtomicTextFile(file))
        store.load()
        val corrupt = File(file.parentFile, "history.json.corrupt")
        assertTrue(corrupt.exists())
        store.save(emptyList())
        assertFalse(corrupt.exists())
    }

    @Test
    fun missingFileLoadsEmptyAndPartlyBadFileStaysInPlace() {
        val file = File(tmp.root, "h.json")
        val store = FileHistoryStore(AtomicTextFile(file))
        assertEquals(emptyList<HistoryEntry>(), store.load())
        assertFalse(file.exists())
        file.writeText("""{"history":[{"url":"ftp://x.example/"},{"url":"https://ok.example/","title":"OK","visited":3}]}""")
        assertEquals(listOf("https://ok.example/"), store.load().map { it.url })
        assertTrue(file.exists())
        assertFalse(File(tmp.root, "h.json.corrupt").exists())
    }
}

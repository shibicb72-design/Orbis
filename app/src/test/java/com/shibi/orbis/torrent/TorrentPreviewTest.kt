package com.shibi.orbis.torrent

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A tiny bencode writer for the tests: Long, String, ByteArray, List and Map (keys sorted as the format wants). */
internal fun bencode(value: Any): ByteArray {
    val out = ByteArrayOutputStream()
    fun w(v: Any) {
        when (v) {
            is Int -> out.write("i${v}e".toByteArray())
            is Long -> out.write("i${v}e".toByteArray())
            is String -> w(v.toByteArray(Charsets.UTF_8))
            is ByteArray -> {
                out.write("${v.size}:".toByteArray())
                out.write(v)
            }
            is List<*> -> {
                out.write('l'.code)
                v.forEach { w(it!!) }
                out.write('e'.code)
            }
            is Map<*, *> -> {
                out.write('d'.code)
                v.entries.sortedBy { it.key as String }.forEach { (k, x) ->
                    w(k as String)
                    w(x!!)
                }
                out.write('e'.code)
            }
            else -> error("unsupported ${v.javaClass}")
        }
    }
    w(value)
    return out.toByteArray()
}

class TorrentPreviewTest {
    private val pieces = ByteArray(20)

    private fun torrent(info: Map<String, Any>) = bencode(mapOf("announce" to "udp://tracker.example:1337", "info" to info))

    @Test
    fun singleFile() {
        val p = TorrentPreview.fromTorrent(torrent(mapOf("name" to "Sintel.mp4", "length" to 129_241_752L, "piece length" to 262_144, "pieces" to pieces)))!!
        assertEquals("Sintel.mp4", p.name)
        assertEquals(129_241_752L, p.totalBytes)
        assertEquals(1, p.fileCount)
    }

    @Test
    fun multiFileSkipsPadFiles() {
        val files =
            listOf(
                mapOf("length" to 100L, "path" to listOf("a.mkv")),
                mapOf("length" to 28L, "path" to listOf(".pad", "28"), "attr" to "p"),
                mapOf("length" to 5L, "path" to listOf("sub", "en.srt")),
            )
        val p = TorrentPreview.fromTorrent(torrent(mapOf("name" to "Sintel", "files" to files, "piece length" to 16_384, "pieces" to pieces)))!!
        assertEquals("Sintel", p.name)
        assertEquals(105L, p.totalBytes)
        assertEquals(2, p.fileCount)
    }

    @Test
    fun v2FileTree() {
        val tree =
            mapOf(
                "Sintel" to
                    mapOf(
                        "movie.mp4" to mapOf("" to mapOf("length" to 1000L, "pieces root" to ByteArray(32))),
                        "sub" to mapOf("en.srt" to mapOf("" to mapOf("length" to 24L))),
                    ),
            )
        val p = TorrentPreview.fromTorrent(torrent(mapOf("name" to "Sintel", "file tree" to tree, "meta version" to 2, "piece length" to 16_384)))!!
        assertEquals(1024L, p.totalBytes)
        assertEquals(2, p.fileCount)
    }

    @Test
    fun depthBombIsRefused() {
        // 17 nested lists inside the info dict.
        val deep = "d4:infod4:name1:a6:lengthi1e4:deep" + "l".repeat(17) + "e".repeat(17) + "ee"
        assertNull(TorrentPreview.fromTorrent(deep.toByteArray()))
        val ok = "d4:infod4:name1:a6:lengthi1e4:deep" + "l".repeat(10) + "e".repeat(10) + "ee"
        assertNotNull(TorrentPreview.fromTorrent(ok.toByteArray()))
    }

    @Test
    fun tokenBombIsRefused() {
        val many = "d4:infod4:name1:a6:lengthi1e4:manyl" + "i0e".repeat(TorrentPreview.MAX_TOKENS) + "eee"
        assertNull(TorrentPreview.fromTorrent(many.toByteArray()))
    }

    @Test
    fun truncatedAndMalformedInputIsRefused() {
        val good = torrent(mapOf("name" to "a", "length" to 10L))
        assertNotNull(TorrentPreview.fromTorrent(good))
        for (cut in listOf(1, 5, good.size / 2, good.size - 1)) assertNull("cut at $cut", TorrentPreview.fromTorrent(good.copyOf(cut)))
        // A string longer than what is left of the input.
        assertNull(TorrentPreview.fromTorrent("d4:infod4:name99999:aee".toByteArray()))
        // Integers: overflow, leading zeros, minus zero.
        assertNull(TorrentPreview.fromTorrent("d4:infod4:name1:a6:lengthi99999999999999999999eee".toByteArray()))
        assertNull(TorrentPreview.fromTorrent("d4:infod4:name1:a6:lengthi012eee".toByteArray()))
        assertNull(TorrentPreview.fromTorrent("d4:infod4:name1:a6:lengthi-0eee".toByteArray()))
        assertNull(TorrentPreview.fromTorrent(ByteArray(0)))
        assertNull(TorrentPreview.fromTorrent("not a torrent".toByteArray()))
        assertNull(TorrentPreview.fromTorrent(bencode(mapOf("announce" to "x"))))
    }

    @Test
    fun negativeLengthIsRefused() {
        assertNull(TorrentPreview.fromTorrent(torrent(mapOf("name" to "a", "length" to -1L))))
        val files = listOf(mapOf("length" to 5L, "path" to listOf("a")), mapOf("length" to -5L, "path" to listOf("b")))
        assertNull(TorrentPreview.fromTorrent(torrent(mapOf("name" to "a", "files" to files))))
        // Two sizes that overflow a Long together.
        val huge = listOf(mapOf("length" to Long.MAX_VALUE, "path" to listOf("a")), mapOf("length" to 1L, "path" to listOf("b")))
        assertNull(TorrentPreview.fromTorrent(torrent(mapOf("name" to "a", "files" to huge))))
    }

    @Test
    fun overTheCapIsRefused() {
        fun padded(n: Int) = torrent(mapOf("name" to "a", "length" to 1L, "x" to "z".repeat(n)))
        // Shrink the padding until the file is exactly one byte over the cap.
        val start = TorrentLimits.MAX_TORRENT_FILE_BYTES + 1 - padded(0).size
        val over = generateSequence(start) { it - 1 }.map(::padded).first { it.size <= TorrentLimits.MAX_TORRENT_FILE_BYTES + 1 }
        assertEquals(TorrentLimits.MAX_TORRENT_FILE_BYTES + 1, over.size)
        assertNull(TorrentPreview.fromTorrent(over))
        val atCap = generateSequence(start) { it - 1 }.map(::padded).first { it.size <= TorrentLimits.MAX_TORRENT_FILE_BYTES }
        assertEquals(TorrentLimits.MAX_TORRENT_FILE_BYTES, atCap.size)
        assertNotNull(TorrentPreview.fromTorrent(atCap))
    }

    @Test
    fun hostileNamesAreShownSanitised() {
        val p = TorrentPreview.fromTorrent(torrent(mapOf("name" to "../../evil‮gpj.exe", "length" to 1L)))!!
        assertEquals("evilgpj.exe", p.name)
        assertNull(TorrentPreview.fromTorrent(torrent(mapOf("name" to "..", "length" to 1L)))!!.name)
        // name.utf-8 wins over name.
        assertEquals("Ünïcode", TorrentPreview.fromTorrent(torrent(mapOf("name" to "x", "name.utf-8" to "Ünïcode", "length" to 1L)))!!.name)
    }

    @Test
    fun magnetPreviewHasOnlyTheName() {
        val p = TorrentPreview.fromMagnet(MagnetLink.parse("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel")!!)
        assertEquals("Sintel", p.name)
        assertNull(p.totalBytes)
        assertNull(p.fileCount)
    }
}

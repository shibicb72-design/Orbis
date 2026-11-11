package com.shibi.orbis.torrent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TorrentPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(vararg segments: String, size: Long = 1, symlink: Boolean = false, pad: Boolean = false) =
        TorrentFileEntry(segments.toList(), size, symlink, pad)

    private fun torrent(name: String, vararg files: TorrentFileEntry, pieces: Int = 1, total: Long? = null) =
        TorrentFiles(name, total ?: files.sumOf { it.size }, pieces, files.toList())

    private fun ok(t: TorrentFiles): TorrentPaths.Plan = (TorrentPaths.plan(t) as TorrentPaths.Result.Ok).plan

    private fun rejected(t: TorrentFiles): TorrentPaths.Rejection = (TorrentPaths.plan(t) as TorrentPaths.Result.Rejected).reason

    @Test
    fun plainMultiFileTorrentKeepsItsNames() {
        val plan = ok(torrent("Sintel", entry("Sintel", "Sintel.mp4"), entry("Sintel", "subs", "en.srt")))
        assertEquals("Sintel", plan.displayName)
        assertEquals(listOf("Sintel/Sintel.mp4", "Sintel/subs/en.srt"), plan.relativePaths)
        assertTrue(plan.renames.isEmpty())
        // Published below Download/Orbis/Sintel/ without the top folder twice.
        assertEquals(listOf("Sintel.mp4", "subs/en.srt"), plan.publishPaths)
        assertEquals("Orbis/Sintel/subs" to "en.srt", TorrentPaths.publishTarget(plan.displayName, plan.publishPaths[1]!!))
        assertEquals("Orbis/Sintel" to "Sintel.mp4", TorrentPaths.publishTarget(plan.displayName, plan.publishPaths[0]!!))
    }

    @Test
    fun singleFileTorrentPublishesIntoAFolderOfItsName() {
        val plan = ok(torrent("movie.mkv", entry("movie.mkv")))
        assertEquals(listOf("movie.mkv"), plan.publishPaths)
        assertEquals("Orbis/movie.mkv" to "movie.mkv", TorrentPaths.publishTarget(plan.displayName, "movie.mkv"))
    }

    @Test
    fun traversalIsRejected() {
        assertEquals(TorrentPaths.Rejection.TRAVERSAL, rejected(torrent("t", entry("t", "..", "etc", "passwd"))))
        assertEquals(TorrentPaths.Rejection.TRAVERSAL, rejected(torrent("t", entry("t", ".", "a"))))
        assertEquals(TorrentPaths.Rejection.TRAVERSAL, rejected(torrent("t", entry(".."))))
    }

    @Test
    fun absoluteAndEmptyPathsAreRejected() {
        // "/etc/passwd" split on '/' starts with an empty segment.
        assertEquals(TorrentPaths.Rejection.EMPTY_PATH, rejected(torrent("t", entry("", "etc", "passwd"))))
        assertEquals(TorrentPaths.Rejection.EMPTY_PATH, rejected(torrent("t", TorrentFileEntry(emptyList(), 1))))
        assertEquals(TorrentPaths.Rejection.EMPTY_PATH, rejected(torrent("t")))
    }

    @Test
    fun separatorsAndNulInsideASegmentAreRejected() {
        assertEquals(TorrentPaths.Rejection.SEPARATOR, rejected(torrent("t", entry("t", "a/b"))))
        assertEquals(TorrentPaths.Rejection.SEPARATOR, rejected(torrent("t", entry("t", "a\\..\\b"))))
        assertEquals(TorrentPaths.Rejection.SEPARATOR, rejected(torrent("t", entry("t", "a\u0000b"))))
    }

    @Test
    fun driveLettersAndUncPrefixesAreRejected() {
        assertEquals(TorrentPaths.Rejection.DRIVE_PREFIX, rejected(torrent("t", entry("C:", "Windows"))))
        assertEquals(TorrentPaths.Rejection.DRIVE_PREFIX, rejected(torrent("t", entry("t", "c:evil"))))
        assertEquals(TorrentPaths.Rejection.SEPARATOR, rejected(torrent("t", entry("\\\\server", "share"))))
    }

    @Test
    fun symlinksAreRejected() {
        assertEquals(TorrentPaths.Rejection.SYMLINK, rejected(torrent("t", entry("t", "link", symlink = true))))
    }

    @Test
    fun depthIsLimited() {
        val deep = Array(TorrentLimits.MAX_DEPTH + 1) { "d$it" }
        assertEquals(TorrentPaths.Rejection.TOO_DEEP, rejected(torrent("t", entry(*deep))))
        val fine = Array(TorrentLimits.MAX_DEPTH) { "d$it" }
        ok(torrent("t", entry(*fine)))
    }

    @Test
    fun reservedNamesAndUnsafeCharactersAreRenamed() {
        val plan = ok(torrent("CON", entry("CON", "aux.txt"), entry("CON", "bad‮name.exe"), entry("CON", "what?.txt")))
        assertEquals("_CON", plan.displayName)
        assertEquals(listOf("_CON/_aux.txt", "_CON/badname.exe", "_CON/what_.txt"), plan.relativePaths)
        assertEquals(mapOf(0 to "_CON/_aux.txt", 1 to "_CON/badname.exe", 2 to "_CON/what_.txt"), plan.renames)
    }

    @Test
    fun collisionsGetACounterAndFoldersStayConsistent() {
        val plan =
            ok(
                torrent(
                    "t",
                    entry("t", "a?b", "1.txt"),
                    entry("t", "a*b", "2.txt"),
                    entry("t", "a?b", "3.txt"),
                    entry("t", "Readme.TXT"),
                    entry("t", "readme.txt"),
                ),
            )
        assertEquals(
            listOf("t/a_b/1.txt", "t/a_b (1)/2.txt", "t/a_b/3.txt", "t/Readme.TXT", "t/readme (1).txt"),
            plan.relativePaths,
        )
    }

    @Test
    fun longNamesAreCapped() {
        val plan = ok(torrent("t", entry("t", "x".repeat(400) + ".bin")))
        val name = plan.relativePaths[0]!!.substringAfter('/')
        assertTrue(name.toByteArray().size <= 200)
        assertTrue(name.endsWith(".bin"))
    }

    @Test
    fun padFilesAreNeitherRenamedNorPublished() {
        val plan = ok(torrent("t", entry("t", "a.bin"), entry(".pad", "123", pad = true), entry("t", "b.bin")))
        assertEquals(listOf("t/a.bin", null, "t/b.bin"), plan.relativePaths)
        assertEquals(listOf("a.bin", null, "b.bin"), plan.publishPaths)
    }

    @Test
    fun emptyNameAfterSanitisingIsRejected() {
        assertEquals(TorrentPaths.Rejection.EMPTY_NAME, rejected(torrent(" ... ", entry("x"))))
        assertEquals(TorrentPaths.Rejection.EMPTY_NAME, rejected(torrent("‮​", entry("x"))))
    }

    @Test
    fun sizeLimits() {
        val many = Array(TorrentLimits.MAX_FILES + 1) { entry("t", "f$it") }
        assertEquals(TorrentPaths.Rejection.TOO_MANY_FILES, rejected(torrent("t", *many)))
        assertEquals(TorrentPaths.Rejection.TOO_MANY_PIECES, rejected(torrent("t", entry("t", "a"), pieces = TorrentLimits.MAX_PIECES + 1)))
        assertEquals(TorrentPaths.Rejection.TOO_LARGE, rejected(torrent("t", entry("t", "a"), total = TorrentLimits.MAX_TOTAL_BYTES + 1)))
    }

    @Test
    fun sourceLimits() {
        assertNull(TorrentPaths.checkSource(TorrentSource.Magnet("magnet:?xt=urn:btih:abc")))
        assertEquals(TorrentErrorKind.INVALID_SOURCE, TorrentPaths.checkSource(TorrentSource.Magnet("https://example.com/a.torrent")))
        assertEquals(
            TorrentErrorKind.UNSAFE_METADATA,
            TorrentPaths.checkSource(TorrentSource.Magnet("magnet:?" + "a".repeat(TorrentLimits.MAX_MAGNET_CHARS))),
        )
        assertNull(TorrentPaths.checkSource(TorrentSource.TorrentFile(ByteArray(TorrentLimits.MAX_TORRENT_FILE_BYTES))))
        assertEquals(
            TorrentErrorKind.UNSAFE_METADATA,
            TorrentPaths.checkSource(TorrentSource.TorrentFile(ByteArray(TorrentLimits.MAX_TORRENT_FILE_BYTES + 1))),
        )
        assertEquals(TorrentErrorKind.INVALID_SOURCE, TorrentPaths.checkSource(TorrentSource.TorrentFile(ByteArray(0))))
    }

    @Test
    fun magnetDisplayNameIsDecodedAndSafe() {
        assertEquals("Big Buck Bunny", TorrentPaths.magnetDisplayName("magnet:?xt=urn:btih:abc&dn=Big+Buck+Bunny"))
        assertEquals("evil.txt", TorrentPaths.magnetDisplayName("magnet:?xt=urn:btih:abc&dn=..%2F..%2Fevil.txt"))
        assertNull(TorrentPaths.magnetDisplayName("magnet:?xt=urn:btih:abc"))
        assertNull(TorrentPaths.magnetDisplayName("magnet:?dn=%2E%2E"))
    }

    @Test
    fun everyPlannedFileIsInsideTheSaveDirectory() {
        val dir = tmp.newFolder("save")
        assertTrue(TorrentPaths.allInside(dir, ok(torrent("t", entry("t", "a"), entry("t", "b", "c")))))
        val escaped = TorrentPaths.Plan("t", listOf("../outside"), emptyMap(), listOf("outside"))
        assertFalse(TorrentPaths.allInside(dir, escaped))
        assertFalse(TorrentPaths.allInside(File(dir, "x"), TorrentPaths.Plan("t", listOf("../x2"), emptyMap(), listOf("x2"))))
    }
}

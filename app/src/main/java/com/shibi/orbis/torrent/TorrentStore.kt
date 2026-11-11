package com.shibi.orbis.torrent

import com.shibi.orbis.storage.AtomicTextFile
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure JSON codec for the torrent index. Unknown or broken entries are skipped; a file that is not an index at all
 * throws [JSONException] so the store can set it aside. Live fields and private entries are never encoded.
 */
object TorrentCodec {
    const val VERSION = 1
    private val ID = Regex("[0-9a-f]{32}")

    fun encode(entries: List<TorrentEntry>): String {
        val array = JSONArray()
        for (e in entries) {
            if (e.isPrivate || e.status == TorrentStatus.REMOVED) continue
            val o = JSONObject()
            o.put("id", e.id)
            o.put("name", e.displayName)
            o.put("total", e.totalBytes)
            o.put("done", e.doneBytes)
            o.put("status", e.status.name)
            o.put("added", e.addedAt)
            o.put("saveDir", e.saveDir)
            e.publishedPath?.let { o.put("published", it) }
            e.errorKind?.let { o.put("error", it.name) }
            e.pauseReason?.let { o.put("pause", it.name) }
            if (e.hasMetadata) o.put("metadata", true)
            array.put(o)
        }
        return JSONObject().put("version", VERSION).put("entries", array).toString()
    }

    @Throws(JSONException::class)
    fun decode(text: String): List<TorrentEntry> {
        val root = JSONObject(text)
        val array = root.getJSONArray("entries")
        val out = ArrayList<TorrentEntry>(array.length())
        for (i in 0 until array.length()) decodeEntry(array.optJSONObject(i))?.let { out += it }
        return out
    }

    private fun decodeEntry(o: JSONObject?): TorrentEntry? {
        if (o == null) return null
        return try {
            val id = o.getString("id")
            if (!ID.matches(id)) return null
            val status = TorrentStatus.entries.firstOrNull { it.name == o.getString("status") } ?: return null
            if (status == TorrentStatus.REMOVED) return null
            val name = o.getString("name")
            if (name.isEmpty()) return null
            val saveDir = o.getString("saveDir")
            if (saveDir.isEmpty()) return null
            TorrentEntry(
                id = id,
                displayName = name,
                totalBytes = o.optLong("total", -1L).coerceAtLeast(-1L),
                doneBytes = o.optLong("done", 0L).coerceAtLeast(0L),
                status = status,
                addedAt = o.optLong("added", 0L),
                saveDir = saveDir,
                publishedPath = if (o.has("published") && !o.isNull("published")) o.getString("published") else null,
                errorKind = o.optString("error").let { k -> TorrentErrorKind.entries.firstOrNull { it.name == k } },
                pauseReason = o.optString("pause").let { k -> PauseReason.entries.firstOrNull { it.name == k } },
                hasMetadata = o.optBoolean("metadata", false),
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/**
 * Everything torrents keep in filesDir/torrents/ (app-private, excluded from backup like the rest of the app):
 * index.json, `<id>.fastresume` and `<id>.torrent` for normal entries only, and session.dat (the engine writes it).
 * Blocking IO: call from an IO dispatcher.
 */
class TorrentStore(private val dir: File) {
    private val index = AtomicTextFile(File(dir, "index.json"))

    val sessionFile: File get() = File(dir, "session.dat")

    class Loaded(val entries: List<TorrentEntry>, val corrupt: Boolean)

    /** A file that cannot be parsed is set aside (index.json.corrupt) and the list starts empty. */
    fun load(): Loaded {
        val text =
            try {
                index.read()
            } catch (e: IOException) {
                return Loaded(emptyList(), corrupt = false)
            } ?: return Loaded(emptyList(), corrupt = false)
        return try {
            Loaded(TorrentCodec.decode(text), corrupt = false)
        } catch (e: JSONException) {
            index.setAside()
            Loaded(emptyList(), corrupt = true)
        }
    }

    @Throws(IOException::class)
    fun save(entries: List<TorrentEntry>) {
        index.write(TorrentCodec.encode(entries))
    }

    fun readResume(id: String): ByteArray? = read(File(dir, "$id.fastresume"))

    @Throws(IOException::class)
    fun writeResume(id: String, bytes: ByteArray) = write(File(dir, "$id.fastresume"), bytes)

    fun readTorrent(id: String): ByteArray? = read(File(dir, "$id.torrent"))

    @Throws(IOException::class)
    fun writeTorrent(id: String, bytes: ByteArray) = write(File(dir, "$id.torrent"), bytes)

    /** Deletes an index that [load] set aside (index.json.corrupt). Never throws. */
    fun deleteSetAside() = index.deleteAside()

    /** Drops the resume data and metadata of [id]. Never throws. */
    fun deleteState(id: String) {
        runCatching { File(dir, "$id.fastresume").delete() }
        runCatching { File(dir, "$id.torrent").delete() }
    }

    /** Ids that have state files on disk (to find leftovers no index entry owns). */
    fun storedIds(): Set<String> =
        runCatching {
            dir.listFiles()?.mapNotNull { f ->
                val n = f.name
                when {
                    n.endsWith(".fastresume") -> n.removeSuffix(".fastresume")
                    n.endsWith(".torrent") -> n.removeSuffix(".torrent")
                    else -> null
                }
            }?.toSet()
        }.getOrNull() ?: emptySet()

    private fun read(f: File): ByteArray? = runCatching { if (f.isFile) f.readBytes() else null }.getOrNull()

    private fun write(f: File, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create directory")
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

package com.shibi.orbis.downloads

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Pure JSON codec for the downloads index. Corrupt or unknown input decodes to fewer entries, never throws. */
object DownloadCodec {
    private const val VERSION = 1

    fun encode(entries: List<DownloadEntry>): String {
        val array = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("id", e.id)
            o.put("name", e.fileName)
            o.put("mime", e.mimeType)
            e.sourceHost?.let { o.put("host", it) }
            o.put("total", e.totalBytes)
            o.put("received", e.receivedBytes)
            o.put("status", e.status.name)
            e.filePath?.let { o.put("path", it) }
            o.put("started", e.startedAt)
            array.put(o)
        }
        return JSONObject().put("version", VERSION).put("entries", array).toString()
    }

    fun decode(text: String?): List<DownloadEntry> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONObject(text).optJSONArray("entries") ?: return emptyList()
            val out = ArrayList<DownloadEntry>(array.length())
            for (i in 0 until array.length()) {
                decodeEntry(array.optJSONObject(i))?.let { out += it }
            }
            out
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun decodeEntry(o: JSONObject?): DownloadEntry? {
        if (o == null) return null
        return try {
            val status = DownloadStatus.entries.firstOrNull { it.name == o.getString("status") } ?: return null
            val name = o.getString("name")
            if (name.isEmpty()) return null
            DownloadEntry(
                id = o.getLong("id"),
                fileName = name,
                mimeType = o.optString("mime", DownloadFileNames.DEFAULT_MIME),
                sourceHost = if (o.has("host") && !o.isNull("host")) o.getString("host") else null,
                totalBytes = o.optLong("total", -1L).coerceAtLeast(-1L),
                receivedBytes = o.optLong("received", 0L).coerceAtLeast(0L),
                status = status,
                filePath = if (o.has("path") && !o.isNull("path")) o.getString("path") else null,
                startedAt = o.optLong("started", 0L),
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/**
 * File-backed index of downloads. Blocking IO: call from an IO dispatcher. Writes go to a temp file then
 * rename, so a crash mid-write leaves the previous index intact.
 */
class DownloadStore(private val file: File) {
    /** Missing, unreadable or corrupt file yields an empty list. */
    fun load(): List<DownloadEntry> =
        try {
            if (file.isFile) DownloadCodec.decode(file.readText(Charsets.UTF_8)) else emptyList()
        } catch (e: IOException) {
            emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    @Throws(IOException::class)
    fun save(entries: List<DownloadEntry>) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        tmp.writeText(DownloadCodec.encode(entries), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

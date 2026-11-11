package com.shibi.orbis.torrent

import com.shibi.orbis.downloads.DownloadFileNames

/**
 * What the confirm sheet shows about a torrent before anything is added: a small bencode reader in plain Kotlin, so
 * the native library is not loaded before the user taps Download and a hostile file meets tight limits first (input
 * size, nesting depth, token count, string lengths bounded by the input, integers checked for overflow).
 *
 * The result is only ever shown. It is never used for a path and never given to the engine: the engine parses the
 * file again after the tap, with its own checks ([TorrentPaths]).
 */
object TorrentPreview {
    const val MAX_DEPTH = 16
    const val MAX_TOKENS = 100_000

    class Preview(
        /** Safe for display ([DownloadFileNames.safeName]); null when the torrent names nothing usable. */
        val name: String?,
        /** Null when unknown. */
        val totalBytes: Long?,
        /** Null when unknown. */
        val fileCount: Int?,
    ) {
        override fun toString(): String = "Preview(files=$fileCount)"
    }

    /** Nothing known: the sheet shows its generic title. */
    val UNKNOWN = Preview(null, null, null)

    /** Null when [bytes] break any limit or are not a torrent (the sheet then shows [UNKNOWN]). */
    fun fromTorrent(bytes: ByteArray): Preview? {
        if (bytes.isEmpty() || bytes.size > TorrentLimits.MAX_TORRENT_FILE_BYTES) return null
        val root = runCatching { Reader(bytes).readTop() }.getOrNull() as? Node.Dict ?: return null
        val info = root.map["info"] as? Node.Dict ?: return null
        val name = ((info.map["name.utf-8"] ?: info.map["name"]) as? Node.Str)?.let { DownloadFileNames.safeName(it.text()) }?.takeIf { it.isNotEmpty() }
        val sizes = v1Sizes(info) ?: v2Sizes(info) ?: return null
        var total = 0L
        for (s in sizes) {
            if (s < 0) return null
            total = runCatching { Math.addExact(total, s) }.getOrNull() ?: return null
        }
        return Preview(name, total, sizes.size)
    }

    /** A magnet carries only its `dn`: size and file count stay unknown. */
    fun fromMagnet(magnet: MagnetLink): Preview = Preview(magnet.displayName, null, null)

    /** `info.length` (one file) or every `info.files[].length` that is not a pad file; null when neither is there. */
    private fun v1Sizes(info: Node.Dict): List<Long>? {
        (info.map["length"] as? Node.Int)?.let { return listOf(it.value) }
        val files = info.map["files"] as? Node.List ?: return null
        val out = ArrayList<Long>(files.items.size)
        for (f in files.items) {
            val d = f as? Node.Dict ?: return null
            val len = d.map["length"] as? Node.Int ?: return null
            val attr = d.map["attr"] as? Node.Str
            if (attr != null && attr.contains('p'.code.toByte())) continue
            out += len.value
        }
        return out
    }

    /** v2: every leaf of `info["file tree"]` (a "" key holding a dict with `length`). */
    private fun v2Sizes(info: Node.Dict): List<Long>? {
        val tree = info.map["file tree"] as? Node.Dict ?: return null
        val out = ArrayList<Long>()
        fun walk(d: Node.Dict): Boolean {
            for ((key, value) in d.map) {
                val child = value as? Node.Dict ?: return false
                if (key.isEmpty()) {
                    out += (child.map["length"] as? Node.Int ?: return false).value
                } else if (!walk(child)) {
                    return false
                }
            }
            return true
        }
        return if (walk(tree)) out else null
    }

    private sealed interface Node {
        class Int(val value: Long) : Node

        class Str(private val bytes: ByteArray, val start: kotlin.Int, val length: kotlin.Int) : Node {
            fun text(): String = String(bytes, start, length, Charsets.UTF_8)

            fun contains(b: Byte): Boolean {
                for (i in start until start + length) if (bytes[i] == b) return true
                return false
            }
        }

        class List(val items: kotlin.collections.List<Node>) : Node

        /** Keys as ISO-8859-1 so every byte maps to one char; the keys looked up are ASCII. */
        class Dict(val map: Map<String, Node>) : Node
    }

    private class Malformed : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0
        private var tokens = 0

        fun readTop(): Node = read(1)

        private fun read(depth: Int): Node {
            if (depth > MAX_DEPTH) throw Malformed()
            if (++tokens > MAX_TOKENS) throw Malformed()
            if (pos >= b.size) throw Malformed()
            return when (b[pos].toInt().toChar()) {
                'i' -> {
                    pos++
                    Node.Int(readInt('e'.code))
                }
                'l' -> {
                    pos++
                    val items = ArrayList<Node>()
                    while (true) {
                        if (pos >= b.size) throw Malformed()
                        if (b[pos] == 'e'.code.toByte()) {
                            pos++
                            break
                        }
                        items += read(depth + 1)
                    }
                    Node.List(items)
                }
                'd' -> {
                    pos++
                    val map = LinkedHashMap<String, Node>()
                    while (true) {
                        if (pos >= b.size) throw Malformed()
                        if (b[pos] == 'e'.code.toByte()) {
                            pos++
                            break
                        }
                        if (++tokens > MAX_TOKENS) throw Malformed()
                        val key = readString()
                        val k = String(b, key.start, key.length, Charsets.ISO_8859_1)
                        map[k] = read(depth + 1)
                    }
                    Node.Dict(map)
                }
                in '0'..'9' -> readString()
                else -> throw Malformed()
            }
        }

        private fun readString(): Node.Str {
            val len = readInt(':'.code)
            if (len < 0 || len > b.size - pos) throw Malformed()
            val s = Node.Str(b, pos, len.toInt())
            pos += len.toInt()
            return s
        }

        /** Digits (one leading '-' allowed for an integer, no leading zeros, no "-0") up to [end]; overflow throws. */
        private fun readInt(end: Int): Long {
            val negative = pos < b.size && b[pos] == '-'.code.toByte()
            if (negative) {
                if (end == ':'.code) throw Malformed()
                pos++
            }
            val first = pos
            var v = 0L
            while (true) {
                if (pos >= b.size) throw Malformed()
                val c = b[pos].toInt()
                if (c == end) break
                if (c < '0'.code || c > '9'.code) throw Malformed()
                v = try {
                    Math.addExact(Math.multiplyExact(v, 10L), (c - '0'.code).toLong())
                } catch (e: ArithmeticException) {
                    throw Malformed()
                }
                pos++
            }
            val digits = pos - first
            if (digits == 0) throw Malformed()
            if (digits > 1 && b[first] == '0'.code.toByte()) throw Malformed()
            if (negative && v == 0L) throw Malformed()
            pos++
            return if (negative) -v else v
        }
    }
}

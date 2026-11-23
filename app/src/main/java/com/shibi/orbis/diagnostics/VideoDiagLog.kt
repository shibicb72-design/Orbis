package com.shibi.orbis.diagnostics

import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the log's text is kept. Blocking calls; [VideoDiagLog] runs them through its `io`. */
interface DiagLogFile {
    fun read(): String?

    fun append(text: String)

    fun replace(text: String)

    fun delete()
}

/** `filesDir/video_diagnostics.txt`: the app's private storage (no Android or Orbis backup holds it). */
class FileDiagLogFile(private val file: File) : DiagLogFile {
    override fun read(): String? = if (file.isFile) file.readText(StandardCharsets.UTF_8) else null

    override fun append(text: String) = file.appendText(text, StandardCharsets.UTF_8)

    override fun replace(text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text, StandardCharsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(text, StandardCharsets.UTF_8)
            temp.delete()
        }
    }

    override fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    companion object {
        const val FILE_NAME = "video_diagnostics.txt"
    }
}

/**
 * The log's text: a header (lines starting "# ") and the entries after it, newest last. At most [capBytes] of UTF-8
 * text: when a new entry would go over, the oldest entries go (down to [TRIM_TO_PERCENT] of the cap, so the file is not
 * rewritten for every line); the header always stays. The memory copy is the truth; file writes run on [io] in order.
 * Main thread.
 */
class VideoDiagLog(
    private val file: DiagLogFile,
    private val io: (Runnable) -> Unit,
    private val capBytes: Int = CAP_BYTES,
) {
    private var header: List<String> = emptyList()
    private val entries = ArrayDeque<String>()
    private var bytes = 0
    private val _version = MutableStateFlow(0)

    /** Changes whenever the text changes. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Reads the file once (blocking; call before the first write). A missing or unreadable file is an empty log. */
    fun load() {
        val text = runCatching { file.read() }.getOrNull().orEmpty()
        header = emptyList()
        entries.clear()
        val lines = text.split('\n').filter { it.isNotEmpty() }
        header = lines.takeWhile { it.startsWith(HEADER_PREFIX) }
        entries.addAll(lines.drop(header.size))
        bytes = sizeOf(header) + entries.sumOf { lineBytes(it) }
        trim()
        changed()
    }

    val isEmpty: Boolean get() = header.isEmpty() && entries.isEmpty()

    /** Size of the text as exported, in bytes. */
    val sizeBytes: Int get() = bytes

    /** Exactly what the screen shows and what Copy and Save write. */
    fun text(): String = (header + entries).joinToString("\n")

    fun lines(): List<String> = header + entries

    /** Replaces the header (each line gets the "# " prefix). Entries stay. */
    fun setHeader(lines: List<String>) {
        header = lines.map { HEADER_PREFIX + it.replace('\n', ' ') }
        bytes = sizeOf(header) + entries.sumOf { lineBytes(it) }
        trim()
        rewrite()
    }

    fun append(line: String) {
        val clean = line.replace('\n', ' ').let { if (it.startsWith("#")) " $it" else it }
        entries.addLast(clean)
        bytes += lineBytes(clean)
        if (bytes > capBytes) {
            trim()
            rewrite()
        } else {
            val text = clean + "\n"
            io { runCatching { file.append(text) } }
            changed()
        }
    }

    /** Empties the log and deletes its file. */
    fun clear() {
        header = emptyList()
        entries.clear()
        bytes = 0
        io { runCatching { file.delete() } }
        changed()
    }

    private fun trim() {
        if (bytes <= capBytes) return
        val target = capBytes.toLong() * TRIM_TO_PERCENT / 100
        while (entries.isNotEmpty() && bytes > target) bytes -= lineBytes(entries.removeFirst())
    }

    private fun rewrite() {
        val text = lines().joinToString("") { it + "\n" }
        io { runCatching { file.replace(text) } }
        changed()
    }

    private fun changed() = _version.update { it + 1 }

    private fun sizeOf(lines: List<String>) = lines.sumOf { lineBytes(it) }

    private fun lineBytes(line: String) = line.toByteArray(StandardCharsets.UTF_8).size + 1

    companion object {
        const val CAP_BYTES = 1_000_000
        const val TRIM_TO_PERCENT = 90
        const val HEADER_PREFIX = "# "
    }
}

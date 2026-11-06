package com.shibi.orbis.storage

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One UTF-8 text file that is only ever replaced whole: written to a temp file and renamed, so a crash mid-write leaves
 * the old file intact. Blocking calls: run them off the main thread.
 */
class AtomicTextFile(private val file: File) {
    /** Null when the file does not exist. Throws [IOException] when it exists but cannot be read. */
    @Synchronized
    fun read(): String? = if (file.isFile) file.readText(StandardCharsets.UTF_8) else null

    @Synchronized
    fun write(text: String) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create directory")
        }
        val temp = File(parent, file.name + ".tmp")
        temp.writeText(text, StandardCharsets.UTF_8)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Renames an unusable file to `<name>.corrupt`, replacing any older one, so the next write cannot destroy it.
     * Never throws. False if the file is still in place afterwards.
     */
    @Synchronized
    fun setAside(): Boolean =
        try {
            val target = File(file.parentFile, file.name + ".corrupt")
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            !file.exists()
        }

    /** Deletes what [setAside] kept. Never throws. */
    @Synchronized
    fun deleteAside() {
        runCatching { File(file.parentFile, file.name + ".corrupt").delete() }
    }
}

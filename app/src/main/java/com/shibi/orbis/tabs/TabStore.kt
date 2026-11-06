package com.shibi.orbis.tabs

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where the tab list lives. Blocking calls: run them off the main thread. */
interface TabStore {
    /** Null when nothing usable is stored (missing or corrupt file). Never throws. */
    fun load(): TabsSnapshot?

    fun save(snapshot: TabsSnapshot)
}

/** `filesDir/tabs/tabs.json`, written to a temp file and renamed so a crash mid-write leaves the old file intact. */
class FileTabStore(private val file: File) : TabStore {
    @Synchronized
    override fun load(): TabsSnapshot? =
        try {
            if (file.isFile) TabCodec.decode(file.readText(StandardCharsets.UTF_8)) else null
        } catch (e: Exception) {
            null
        }

    @Synchronized
    override fun save(snapshot: TabsSnapshot) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create tab directory")
        }
        val temp = File(parent, file.name + ".tmp")
        temp.writeText(TabCodec.encode(snapshot), StandardCharsets.UTF_8)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

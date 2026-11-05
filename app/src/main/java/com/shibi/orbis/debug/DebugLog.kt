package com.shibi.orbis.debug

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Local-only ring log (max [MAX_LINES] lines) at filesDir/debug_log.txt. Settings-only UI. */
class DebugLog(filesDir: File, private val clock: Clock = Clock.systemDefaultZone()) {
    private val file = File(filesDir, FILE_NAME)
    private val lock = Any()
    private var cache: ArrayList<String>? = null
    private val _version = MutableStateFlow(0)

    val version: StateFlow<Int> = _version.asStateFlow()

    private fun formatter(zone: ZoneId) =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)

    private fun loaded(): ArrayList<String> =
        cache ?: ArrayList<String>().also { list ->
            runCatching { if (file.exists()) list += file.readLines().takeLast(MAX_LINES) }
            cache = list
        }

    fun log(tag: String, msg: String) {
        val line =
            "${formatter(clock.zone).format(Instant.ofEpochMilli(clock.millis()))} $tag ${msg.replace('\n', ' ')}"
        synchronized(lock) {
            val list = loaded()
            list += line
            runCatching {
                if (list.size > MAX_LINES) {
                    while (list.size > MAX_LINES) list.removeAt(0)
                    file.writeText(list.joinToString("\n", postfix = "\n"))
                } else {
                    file.appendText(line + "\n")
                }
            }
        }
        _version.update { it + 1 }
    }

    fun lines(): List<String> = synchronized(lock) { loaded().toList() }

    fun readAll(): String = lines().joinToString("\n")

    fun clear() {
        synchronized(lock) {
            loaded().clear()
            runCatching { file.writeText("") }
        }
        _version.update { it + 1 }
    }

    companion object {
        const val FILE_NAME = "debug_log.txt"
        const val MAX_LINES = 500
    }
}
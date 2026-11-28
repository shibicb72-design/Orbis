package com.shibi.orbis.diagnostics

import com.shibi.orbis.engine.DiagTab
import com.shibi.orbis.engine.hostOfUrl
import com.shibi.orbis.stats.BlockKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONObject

class MemoryVideoDiagPrefs : VideoDiagPrefs {
    override var recording = false
        private set
    override var recordingSince = 0L
        private set
    override var includeSiteNames = false
    override var privateTabs = false
    override var lastEntryAt = 0L

    override fun setRecording(on: Boolean, since: Long) {
        recording = on
        recordingSince = since
    }
}

class MemoryDiagLogFile : DiagLogFile {
    var text: String? = null

    override fun read(): String? = text

    override fun append(text: String) {
        this.text = (this.text ?: "") + text
    }

    override fun replace(text: String) {
        this.text = text
    }

    override fun delete() {
        text = null
    }
}

class MutableClock(var millis: Long = Instant.parse("2026-11-23T10:00:00Z").toEpochMilli()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(millis)

    override fun millis(): Long = millis
}

class FakeTab(
    var url: String? = "https://www.example.com/watch/secret-path?q=private-query#frag",
    override val isPrivate: Boolean = false,
) : DiagTab {
    var blocked: Map<BlockKind, Int> = emptyMap()
    var badge: String? = null
    var front = true
    var active = true
    var full = false
    var shield: Boolean? = true

    override fun pageUrl(): String? = url

    override fun blockedOnPage(): Map<BlockKind, Int> = blocked

    override fun uboBadge(): String? = badge

    override fun inFront(): Boolean = front

    override fun awake(): Boolean = active

    override fun fullScreen(): Boolean = full

    override fun shieldOn(): Boolean? = shield
}

class FakeNetwork : DiagNetworkSource {
    var listener: ((ConnectionKind) -> Unit)? = null

    override fun start(onChange: (ConnectionKind) -> Unit) {
        listener = onChange
    }

    override fun stop() {
        listener = null
    }
}

/** A recorder with everything in memory. [engineCalls] holds (recording, privateTabs, recording as stored then). */
class DiagHarness {
    val prefs = MemoryVideoDiagPrefs()
    val file = MemoryDiagLogFile()
    val clock = MutableClock()
    var elapsed = 1_000_000L
    val network = FakeNetwork()
    val engineCalls = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
    val log = VideoDiagLog(file, io = { it.run() })
    val diag =
        VideoDiagnostics(
            prefs = prefs,
            log = log,
            clock = clock,
            elapsed = { elapsed },
            environment = { listOf("Orbis 0.8.4 (31), engine GeckoView 160.0.1", "Tracking protection: strict") },
            hostOf = ::hostOfUrl,
            network = network,
            applyEngine = { rec, priv -> engineCalls += Triple(rec, priv, prefs.recording) },
        )

    fun text(): String = log.text()

    fun advance(millis: Long) {
        clock.millis += millis
        elapsed += millis
    }
}

fun media(id: Int, kind: String = "video", source: String = "blob", same: Boolean = true, frame: Boolean = false): JSONObject =
    JSONObject().put("type", "media").put("id", id).put("kind", kind).put("source", source).put("same", same).put("frame", frame)

fun event(id: Int, name: String, t: Double = 1.5, pos: Double = 0.0, ahead: Double = 4.25): JSONObject {
    val o =
        JSONObject().put("type", "event").put("id", id).put("name", name).put("t", t).put("ready", 4).put("network", 2)
            .put("pos", pos).put("ahead", ahead).put("finite", true)
    when (name) {
        "loadstart" -> o.put("again", false)
        "error" -> o.put("code", 2)
        "resize" -> o.put("w", 1280).put("h", 720)
    }
    return o
}

fun sample(id: Int, w: Int = 1280, h: Int = 720): JSONObject =
    JSONObject().put("type", "sample").put("id", id).put("ahead", 8.0).put("w", w).put("h", h).put("frames", 1520).put("dropped", 3)

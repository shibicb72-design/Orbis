package com.shibi.orbis.diagnostics

import com.shibi.orbis.engine.DiagSink
import com.shibi.orbis.engine.DiagTab
import com.shibi.orbis.engine.DiagTabEvent
import java.time.Clock
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** The three switches of Settings > Video diagnostics. */
data class VideoDiagSwitches(
    val recording: Boolean = false,
    val includeSiteNames: Boolean = false,
    val privateTabs: Boolean = false,
)

/**
 * Settings > Video diagnostics: the recorder. It writes the log ([VideoDiagLog]) only while recording is on, on this
 * phone only, and sends nothing anywhere. Pages appear as "site N" (numbered in order of appearance in the log, in
 * memory only; the numbers start again with each start of Orbis), followed by the host name only while "Include site
 * names" is on. No address, path, query, title or page text is ever written: the page's messages carry none, and the
 * tab's address is used only to find its host. Private tabs are left out unless "Also record private tabs" is on, and
 * that switch goes off whenever recording does. Recording turns itself off [MAX_AGE_MILLIS] after it was switched on;
 * the log is deleted [MAX_AGE_MILLIS] after its last entry. Main thread.
 *
 * [applyEngine] enables or disables the page script (see DiagExtensionHolder); it is always called after the switch
 * is stored, so "off" is on disk before the script is taken out.
 */
class VideoDiagnostics(
    private val prefs: VideoDiagPrefs,
    val log: VideoDiagLog,
    private val clock: Clock,
    private val elapsed: () -> Long,
    /** Header lines about the app, the phone and its settings (version, engine, model, Android, uBO, DNS, HTTPS). */
    private val environment: () -> List<String>,
    private val hostOf: (String) -> String?,
    private val network: DiagNetworkSource?,
    private val applyEngine: (recording: Boolean, privateTabs: Boolean) -> Unit,
) : DiagSink {
    private val _switches = MutableStateFlow(VideoDiagSwitches())
    val switches: StateFlow<VideoDiagSwitches> = _switches.asStateFlow()

    private var recording = false
    private var privateTabs = false

    /** When the last line was written; on disk at most [LAST_ENTRY_PERSIST_MILLIS] behind (see [noteEntry]). */
    private var lastEntry = 0L

    private val siteNumbers = HashMap<String, Int>()
    private val tabs = WeakHashMap<DiagTab, TabInfo>()
    private var nextTab = 1

    private var foreground: Boolean? = null
    private var pictureInPicture = false
    private var connection: ConnectionKind? = null

    private class Element(val number: Int, var video: Boolean?) {
        var stallStartedAt: Long? = null
    }

    private class TabInfo(val number: Int) {
        var page: String? = null
        var site: String = "site ?"
        var host: String? = null
        val elements = HashMap<Pair<Any, Int>, Element>()
        var lastElement = 0
        var hlsWritten = false
        var lastBlocking: String? = null
        var windowStart = Long.MIN_VALUE
        var windowLines = 0
        var overflowWritten = false
    }

    /** At process start, before anything reads the log: loads it, applies the age limits, then sets the engine. */
    fun start() {
        log.load()
        // The stored time may be up to a minute behind the true last line: count from a minute later, never earlier.
        lastEntry = prefs.lastEntryAt.let { if (it > 0L) it + LAST_ENTRY_PERSIST_MILLIS else 0L }
        recording = prefs.recording
        privateTabs = recording && prefs.privateTabs
        if (!recording && prefs.privateTabs) prefs.privateTabs = false
        publish()
        checkAge()
        if (recording) {
            write("Orbis started; site and tab numbers start again.")
            network?.start(::onConnection)
        }
        applyEngine(recording, privateTabs)
    }

    fun setRecording(on: Boolean) {
        if (on == recording) return
        if (on) {
            prefs.setRecording(true, clock.millis())
            recording = true
            privateTabs = false
            prefs.privateTabs = false
            resetMemory()
            writeHeader()
            write("Recording on.")
            writeAppState()
            network?.start(::onConnection)
            publish()
            applyEngine(true, false)
        } else {
            write("Recording off.")
            stop()
        }
    }

    fun setIncludeSiteNames(on: Boolean) {
        if (!recording || on == prefs.includeSiteNames) return
        prefs.includeSiteNames = on
        write("Include site names: ${onOff(on)}")
        publish()
    }

    fun setPrivateTabs(on: Boolean) {
        if (!recording || on == privateTabs) return
        prefs.privateTabs = on
        privateTabs = on
        write("Also record private tabs: ${onOff(on)}")
        publish()
        applyEngine(true, on)
    }

    /** "Delete log", Clear browsing data and Clear on exit. With recording on a new header starts the log again. */
    fun deleteLog() {
        log.clear()
        setLastEntry(0L)
        resetMemory()
        if (recording) writeHeader()
    }

    /**
     * Applies the two 7-day limits now. Returns how long until the next one is due (millis), or null when none is
     * pending. The app calls it at start and on a timer while Orbis runs.
     */
    fun checkAge(): Long? {
        val now = clock.millis()
        if (recording && now >= prefs.recordingSince + MAX_AGE_MILLIS) {
            write("Recording turned off after 7 days.")
            stop()
        }
        val last = lastEntry
        if (!log.isEmpty && (last == 0L || now >= last + MAX_AGE_MILLIS)) {
            log.clear()
            setLastEntry(0L)
            resetMemory()
        }
        val due =
            listOfNotNull(
                (prefs.recordingSince + MAX_AGE_MILLIS).takeIf { recording },
                (lastEntry + MAX_AGE_MILLIS).takeIf { !log.isEmpty },
            ).minOrNull()
        return due?.let { (it - now).coerceAtLeast(0L) }
    }

    /** When the last line was written (epoch millis); 0 for none. */
    fun lastEntryAt(): Long = lastEntry

    fun onForeground(inFront: Boolean) {
        if (foreground == inFront) return
        foreground = inFront
        if (recording) write(if (inFront) "Orbis in front" else "Orbis in the background")
    }

    fun onPictureInPicture(on: Boolean) {
        if (pictureInPicture == on) return
        pictureInPicture = on
        if (recording) write("picture-in-picture ${onOff(on)}")
    }

    private fun onConnection(kind: ConnectionKind) {
        if (!recording || connection == kind) return
        connection = kind
        write("connection: ${kind.label}")
    }

    // ---- DiagSink ----------------------------------------------------------------------------------------------

    override fun mayRecord(tab: DiagTab): Boolean = recording && (!tab.isPrivate || privateTabs)

    override fun onPageMessage(tab: DiagTab, frame: Any, message: JSONObject) {
        if (!mayRecord(tab)) return
        val msg = parseDiagMessage(message) ?: return
        val info = tabInfo(tab)
        when (msg) {
            is DiagMessage.Media -> {
                val key = frame to msg.id
                val existing = info.elements[key]
                if (existing == null && info.elements.size >= MAX_ELEMENTS_PER_TAB) return
                val element = existing ?: Element(nextElementNumber(info), msg.video).also { info.elements[key] = it }
                element.video = msg.video
                writeTab(tab, info, formatMedia(msg, mediaName(element.video, element.number), first = existing == null))
            }
            is DiagMessage.Event -> {
                val element = elementFor(info, frame, msg.id) ?: return
                val name = mediaName(element.video, element.number)
                val now = elapsed()
                writeTab(tab, info, formatEvent(msg, name))
                val started = element.stallStartedAt
                when {
                    msg.event == MediaEvent.WAITING -> if (started == null) element.stallStartedAt = now
                    msg.event in STALL_ENDS && started != null -> {
                        element.stallStartedAt = null
                        writeTab(tab, info, formatStallEnd(name, (now - started) / 1000.0, msg.event))
                    }
                }
            }
            is DiagMessage.Sample -> {
                val element = elementFor(info, frame, msg.id) ?: return
                writeTab(tab, info, formatSample(msg, mediaName(element.video, element.number), element.video))
                val blocking = formatBlocking(tab.blockedOnPage(), tab.uboBadge())
                if (blocking != null && blocking != info.lastBlocking) {
                    info.lastBlocking = blocking
                    writeTab(tab, info, blocking)
                }
            }
            is DiagMessage.Hls -> {
                if (info.hlsWritten) return
                info.hlsWritten = true
                writeTab(tab, info, formatHls(msg))
            }
            is DiagMessage.Page -> writeTab(tab, info, formatPage(msg))
        }
    }

    override fun onFrameGone(tab: DiagTab, frame: Any) {
        tabs[tab]?.elements?.keys?.removeAll { it.first === frame }
    }

    override fun onTabEvent(tab: DiagTab, event: DiagTabEvent) {
        if (!mayRecord(tab)) return
        // Only tabs whose pages are already in the log.
        val info = tabs[tab] ?: return
        val words =
            when (event) {
                DiagTabEvent.IN_FRONT -> "tab in front"
                DiagTabEvent.NOT_IN_FRONT -> "tab not in front"
                DiagTabEvent.WOKEN -> "tab woken"
                DiagTabEvent.PUT_TO_SLEEP -> "tab put to sleep"
                DiagTabEvent.FULL_SCREEN_ON -> "full screen on"
                DiagTabEvent.FULL_SCREEN_OFF -> "full screen off"
                DiagTabEvent.SHIELD_ON -> "shield on"
                DiagTabEvent.SHIELD_OFF -> "shield off"
            }
        writeTab(tab, info, words)
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private fun stop() {
        if (lastEntry > 0L) prefs.lastEntryAt = lastEntry
        recording = false
        privateTabs = false
        prefs.setRecording(false, 0L)
        prefs.privateTabs = false
        network?.stop()
        resetMemory()
        publish()
        applyEngine(false, false)
    }

    private fun resetMemory() {
        siteNumbers.clear()
        tabs.clear()
        nextTab = 1
        connection = null
    }

    private fun nextElementNumber(info: TabInfo): Int = ++info.lastElement

    private fun elementFor(info: TabInfo, frame: Any, id: Int): Element? {
        info.elements[frame to id]?.let { return it }
        if (info.elements.size >= MAX_ELEMENTS_PER_TAB) return null
        return Element(nextElementNumber(info), null).also { info.elements[frame to id] = it }
    }

    /** The tab's bookkeeping; a new page (another address) gets its site and a line with the tab's state. */
    private fun tabInfo(tab: DiagTab): TabInfo {
        val info = tabs.getOrPut(tab) { TabInfo(nextTab++) }
        val url = tab.pageUrl()
        if (url != info.page || info.windowStart == Long.MIN_VALUE) {
            info.page = url
            val host = url?.let(hostOf)?.lowercase()
            info.host = host
            info.site = host?.let { "site " + siteNumbers.getOrPut(it) { siteNumbers.size + 1 } } ?: "site ?"
            info.hlsWritten = false
            info.lastBlocking = null
            info.windowStart = elapsed()
            info.windowLines = 0
            info.overflowWritten = false
            val shield = tab.shieldOn()?.let { onOff(it) } ?: "not known"
            writeTab(
                tab,
                info,
                "new page: tab ${if (tab.inFront()) "in front" else "not in front"}, " +
                    "${if (tab.awake()) "awake" else "asleep"}, full screen ${onOff(tab.fullScreen())}, shield $shield",
            )
        }
        return info
    }

    private fun who(tab: DiagTab, info: TabInfo): String {
        val host = info.host?.takeIf { prefs.includeSiteNames }
        val site = if (host != null) "${info.site} ($host)" else info.site
        val private = if (tab.isPrivate) " (private)" else ""
        return "$site tab ${info.number}$private"
    }

    /** A line about one page, at most [MAX_LINES_PER_MINUTE] a minute per page, then one line saying so. */
    private fun writeTab(tab: DiagTab, info: TabInfo, what: String) {
        val now = elapsed()
        if (now - info.windowStart >= 60_000L) {
            info.windowStart = now
            info.windowLines = 0
            info.overflowWritten = false
        }
        if (info.windowLines >= MAX_LINES_PER_MINUTE) {
            if (!info.overflowWritten) {
                info.overflowWritten = true
                write("${who(tab, info)}: … more events not recorded")
            }
            return
        }
        info.windowLines++
        write("${who(tab, info)}: $what")
    }

    private fun write(what: String) {
        if (!recording) return
        val now = clock.millis()
        log.append("${diagTime(now, clock.zone)} $what")
        noteEntry(now)
    }

    private fun writeHeader() {
        val now = clock.millis()
        log.setHeader(
            listOf("Orbis video diagnostics log", "Header written ${diagDateTime(now, clock.zone)}") +
                environment() +
                listOf(
                    "Include site names: ${onOff(prefs.includeSiteNames)}. Also record private tabs: ${onOff(privateTabs)}.",
                    "Blocking counts: requests blocked by the engine's tracking protection, per page; uBlock Origin badge where Orbis receives it.",
                    "uBlock Origin on or off for a site: not known to Orbis",
                    "Sites and tabs are numbered in order of appearance; the numbers start again when Orbis starts.",
                ),
        )
        noteEntry(now)
    }

    /** Stores the time of the last line at most once a minute (not on every line), and always when recording stops. */
    private fun noteEntry(now: Long) {
        lastEntry = now
        if (!recording || now - prefs.lastEntryAt >= LAST_ENTRY_PERSIST_MILLIS) prefs.lastEntryAt = now
    }

    private fun setLastEntry(value: Long) {
        lastEntry = value
        prefs.lastEntryAt = value
    }

    private fun writeAppState() {
        foreground?.let { write(if (it) "Orbis in front" else "Orbis in the background") }
        write("picture-in-picture ${onOff(pictureInPicture)}")
    }

    private fun publish() {
        _switches.value = VideoDiagSwitches(recording, prefs.includeSiteNames, privateTabs)
    }

    companion object {
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val MAX_LINES_PER_MINUTE = 120
        const val MAX_ELEMENTS_PER_TAB = 500
        const val LAST_ENTRY_PERSIST_MILLIS = 60_000L
        private val STALL_ENDS = setOf(MediaEvent.PLAYING, MediaEvent.PAUSE, MediaEvent.ENDED, MediaEvent.EMPTIED, MediaEvent.ERROR)
    }
}

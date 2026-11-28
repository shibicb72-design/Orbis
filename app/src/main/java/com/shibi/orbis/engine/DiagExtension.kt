package com.shibi.orbis.engine

import com.shibi.orbis.stats.BlockKind
import java.util.WeakHashMap
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * One tab as video diagnostics sees it. Main thread. [pageUrl] is read only to find the page's site; the recorder never
 * writes it down.
 */
interface DiagTab {
    val isPrivate: Boolean

    fun pageUrl(): String?

    /** Requests the engine's tracking protection blocked on the page shown now, by kind. */
    fun blockedOnPage(): Map<BlockKind, Int>

    /** uBlock Origin's badge for this tab as Orbis last received it, or null. */
    fun uboBadge(): String?

    fun inFront(): Boolean

    fun awake(): Boolean

    fun fullScreen(): Boolean

    /** Tracking protection for this tab's site: true on, false off, null not known yet. */
    fun shieldOn(): Boolean?
}

enum class DiagTabEvent { IN_FRONT, NOT_IN_FRONT, WOKEN, PUT_TO_SLEEP, FULL_SCREEN_ON, FULL_SCREEN_OFF, SHIELD_ON, SHIELD_OFF }

/** Where the engine hands what the diag extension and the tabs report (the app's recorder). Main thread. */
interface DiagSink {
    /** Whether a page in [tab] may start recording now (recording on; a private tab only with its switch on). */
    fun mayRecord(tab: DiagTab): Boolean

    /** One message from the content script of one frame ([frame] tells frames apart; it is never written down). */
    fun onPageMessage(tab: DiagTab, frame: Any, message: JSONObject)

    fun onFrameGone(tab: DiagTab, frame: Any)

    fun onTabEvent(tab: DiagTab, event: DiagTabEvent)
}

/** True only for a port opened by the diag extension's content script in [session] itself. */
internal fun diagSenderOk(extensionId: String?, environmentType: Int?, senderSession: Any?, session: Any): Boolean =
    extensionId == DiagExtensionHolder.ID &&
        environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT &&
        senderSession === session

/** The script's first message, exactly `{"type":"hello"}`. */
internal fun isDiagHello(obj: JSONObject): Boolean = obj.length() == 1 && obj.opt("type") == "hello"

/** What the diag extension needs next to match the switches; see [DiagExtensionHolder]. */
internal enum class DiagStep { NONE, INSTALL, DISABLE, SET_PRIVATE, ENABLE }

/**
 * The next change to the diag extension. [installed] false: it is installed only once recording is wanted (never
 * while off). Off comes first, then the private-tab permission, then on, so an extension is never enabled with a
 * private-tab permission it should not have.
 *
 * No step is repeated without a change: [privateAskedNotTaken] is the private-tab value that was asked for and that the
 * returned extension still did not report (null: none); that value is not asked for again. [stuck] holds the ENABLE or
 * DISABLE step that returned without the wanted state; it is not asked for again. What would follow comes next.
 */
internal fun diagNextStep(
    installed: Boolean,
    enabled: Boolean,
    allowedInPrivate: Boolean,
    wantRecording: Boolean,
    wantPrivate: Boolean,
    privateAskedNotTaken: Boolean? = null,
    stuck: Set<DiagStep> = emptySet(),
): DiagStep {
    val wantedPrivate = wantRecording && wantPrivate
    return when {
        !installed -> if (wantRecording) DiagStep.INSTALL else DiagStep.NONE
        !wantRecording && enabled && DiagStep.DISABLE !in stuck -> DiagStep.DISABLE
        privateBrowsingChangeNeeded(allowedInPrivate, wantedPrivate) && privateAskedNotTaken != wantedPrivate -> DiagStep.SET_PRIVATE
        wantRecording && !enabled && DiagStep.ENABLE !in stuck -> DiagStep.ENABLE
        else -> DiagStep.NONE
    }
}

/** The four controller calls the diag extension needs, and what is read from the extension they return. */
internal interface DiagControl<E : Any> {
    fun enabled(ext: E): Boolean

    fun allowedInPrivate(ext: E): Boolean

    /** Runs [step] (not NONE) on [current] (null only for INSTALL) and calls [ok] with the returned extension, or [fail]. */
    fun run(step: DiagStep, current: E?, wantPrivate: Boolean, ok: (E?) -> Unit, fail: (Throwable?) -> Unit)
}

/**
 * Walks the diag extension to the wanted state, one finished step after the other. It holds the memory that stops a
 * step from being asked again when the engine did not take it. Main thread.
 */
internal class DiagStepper<E : Any>(
    private val control: DiagControl<E>,
    private val log: (String) -> Unit,
    private val onExtension: (E) -> Unit,
) {
    var extension: E? = null
    private var looked = false
    private var busy = false
    private var wantRecording = false
    private var wantPrivate = false
    private var privateAskedNotTaken: Boolean? = null
    private val stuck = mutableSetOf<DiagStep>()

    /** The earlier run's extension is known ([current] null: none installed). */
    fun start(current: E?) {
        extension = current
        looked = true
        apply()
    }

    fun set(recording: Boolean, privateTabs: Boolean) {
        val priv = recording && privateTabs
        if (recording != wantRecording || priv != wantPrivate) {
            privateAskedNotTaken = null
            stuck.clear()
        }
        wantRecording = recording
        wantPrivate = priv
        apply()
    }

    private fun apply() {
        if (!looked || busy) return
        val current = extension
        val next =
            diagNextStep(
                installed = current != null,
                enabled = current?.let(control::enabled) == true,
                allowedInPrivate = current?.let(control::allowedInPrivate) == true,
                wantRecording = wantRecording,
                wantPrivate = wantPrivate,
                privateAskedNotTaken = privateAskedNotTaken,
                stuck = stuck,
            )
        if (next == DiagStep.NONE) return
        log("diag extension step: ${next.name}")
        busy = true
        val asked = wantPrivate
        control.run(
            next,
            current,
            asked,
            { updated ->
                busy = false
                if (updated != null) {
                    extension = updated
                    onExtension(updated)
                    noteResult(next, updated, asked)
                    apply()
                }
            },
            {
                busy = false
                log("diag extension change failed: ${it?.javaClass?.simpleName}")
            },
        )
    }

    private fun noteResult(step: DiagStep, updated: E, asked: Boolean) {
        when (step) {
            DiagStep.SET_PRIVATE -> {
                val now = control.allowedInPrivate(updated)
                if (now != asked) {
                    privateAskedNotTaken = asked
                    log("diag extension: private-tab permission stays $now")
                }
            }
            DiagStep.ENABLE ->
                if (!control.enabled(updated)) {
                    stuck.add(step)
                    log("diag extension: enabled stays false")
                }
            DiagStep.DISABLE ->
                if (control.enabled(updated)) {
                    stuck.add(step)
                    log("diag extension: enabled stays true")
                }
            else -> Unit
        }
    }
}

/** Blocked requests on the page shown now, by kind. Starts again when the page address changes. Main thread. */
internal class PageBlockCounter {
    private var page: String? = null
    private val counts = LinkedHashMap<BlockKind, Int>()

    fun count(kind: BlockKind, pageUrl: String?) {
        if (pageUrl != page) {
            page = pageUrl
            counts.clear()
        }
        counts[kind] = (counts[kind] ?: 0) + 1
    }

    fun snapshot(pageUrl: String?): Map<BlockKind, Int> = if (pageUrl == page) counts.toMap() else emptyMap()
}

/**
 * The built-in video diagnostics extension (assets/extensions/diag). It is installed only the first time recording is
 * switched on and is enabled exactly while recording is on (EnableSource.APP), so its content script is not in any page
 * while recording is off. The app asks the engine to allow it in private tabs only while "Also record private tabs" is
 * on, and asks for each value once (see [DiagStepper]); the engine may keep the permission at true. Then the script is
 * present in a private page but listens to nothing and sends only its `hello`: it waits for the app's "record" answer
 * before it listens to anything, and the app answers only through [DiagSink.mayRecord], which is false for a private
 * tab while its switch is off. The manifest version stays "1.0": a new version would make the engine reinstall it
 * enabled. Main thread.
 */
internal class DiagExtensionHolder(private val log: (String) -> Unit) {
    var sink: DiagSink? = null

    /** uBlock Origin's own badge for a tab (set by the engine). */
    var badgeFor: (GeckoSession) -> String? = { null }

    private var runtime: GeckoRuntime? = null
    private var extension: WebExtension? = null
    private val stepper =
        DiagStepper(
            object : DiagControl<WebExtension> {
                override fun enabled(ext: WebExtension) = ext.metaData?.enabled == true

                override fun allowedInPrivate(ext: WebExtension) = ext.metaData?.allowedInPrivateBrowsing == true

                override fun run(
                    step: DiagStep,
                    current: WebExtension?,
                    wantPrivate: Boolean,
                    ok: (WebExtension?) -> Unit,
                    fail: (Throwable?) -> Unit,
                ) {
                    val controller = runtime?.webExtensionController ?: return fail(null)
                    val result: GeckoResult<WebExtension> =
                        when (step) {
                            DiagStep.INSTALL -> controller.ensureBuiltIn(ASSET_URL, ID)
                            DiagStep.DISABLE -> controller.disable(current!!, WebExtensionController.EnableSource.APP)
                            DiagStep.SET_PRIVATE -> controller.setAllowedInPrivateBrowsing(current!!, wantPrivate)
                            DiagStep.ENABLE -> controller.enable(current!!, WebExtensionController.EnableSource.APP)
                            DiagStep.NONE -> return
                        }
                    result.accept({ ok(it) }, { fail(it) })
                }
            },
            log,
        ) { updated ->
            extension = updated
            attachAll(updated)
        }
    private val sessions = WeakHashMap<GeckoSession, DiagTab>()
    private var selected: GeckoSession? = null

    /** The runtime exists: finds an extension installed by an earlier run, then matches the setting. */
    fun onRuntime(rt: GeckoRuntime) {
        runtime = rt
        rt.webExtensionController.list().accept(
            { list ->
                extension = list.orEmpty().firstOrNull { it.id == ID }
                extension?.let(::attachAll)
                stepper.start(extension)
            },
            { stepper.start(extension) },
        )
    }

    fun set(recording: Boolean, privateTabs: Boolean) = stepper.set(recording, privateTabs)

    fun register(session: GeckoSession, tab: DiagTab) {
        sessions[session] = tab
        extension?.let { attach(session, tab, it) }
    }

    fun tabEvent(session: GeckoSession, event: DiagTabEvent) {
        val tab = sessions[session] ?: return
        sink?.onTabEvent(tab, event)
    }

    fun isSelected(session: GeckoSession): Boolean = selected === session

    /** The tab in front changed (null: none, or the private-tab lock hides it). */
    fun selected(session: GeckoSession?) {
        val previous = selected
        if (previous === session) return
        selected = session
        previous?.let { tabEvent(it, DiagTabEvent.NOT_IN_FRONT) }
        session?.let { tabEvent(it, DiagTabEvent.IN_FRONT) }
    }

    private fun attachAll(ext: WebExtension) {
        for ((session, tab) in sessions.entries.toList()) attach(session, tab, ext)
    }

    private fun attach(session: GeckoSession, tab: DiagTab, ext: WebExtension) {
        runCatching {
            session.webExtensionController.setMessageDelegate(ext, delegateFor(session, tab), NATIVE_APP)
        }.onFailure { log("diag attach failed: ${it.javaClass.simpleName}") }
    }

    private fun delegateFor(session: GeckoSession, tab: DiagTab) =
        object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val sender = port.sender
                if (!diagSenderOk(sender.webExtension.id, sender.environmentType, sender.session, session)) {
                    runCatching { port.disconnect() }
                    return
                }
                port.setDelegate(
                    object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val obj = message as? JSONObject ?: return
                            val out = sink ?: return
                            if (isDiagHello(obj)) {
                                if (out.mayRecord(tab)) runCatching { port.postMessage(JSONObject().put("type", "record")) }
                                return
                            }
                            out.onPageMessage(tab, port, obj)
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            sink?.onFrameGone(tab, port)
                        }
                    },
                )
            }
        }

    companion object {
        const val ID = "diag@orbis.shibi.com"
        const val ASSET_URL = "resource://android/assets/extensions/diag/"
        const val NATIVE_APP = "orbisDiag"
    }
}

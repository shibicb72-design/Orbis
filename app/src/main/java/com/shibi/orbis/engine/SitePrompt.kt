package com.shibi.orbis.engine

import android.net.Uri
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A question a web page asked the user. No engine types: the UI reads these and answers through the methods.
 * [originHost] comes from the session's COMMITTED url, never from page text, so a page cannot dress up the dialog
 * title as another site. The text fields (message, labels) ARE page-supplied: show them as body text only.
 *
 * Every prompt completes exactly once. The first of its answer methods or [dismiss] wins; later calls do nothing, so
 * a UI click racing the engine dismissing the prompt (navigation, tab close, crash) cannot complete twice.
 */
sealed class SitePrompt(val originHost: String) {
    private val finished = AtomicBoolean(false)

    /** Set by [PromptQueue] so a finished prompt leaves the queue. */
    internal var onFinished: ((SitePrompt) -> Unit)? = null

    val isFinished: Boolean get() = finished.get()

    /** Answers "no / cancel / nothing chosen". Safe to call any number of times. */
    fun dismiss() {
        finish { deliverDismissal() }
    }

    protected abstract fun deliverDismissal()

    protected fun finish(deliver: () -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        try {
            deliver()
        } finally {
            onFinished?.invoke(this)
        }
    }

    /** JavaScript alert(). */
    class Alert(
        originHost: String,
        val message: String,
        private val respond: () -> Unit,
    ) : SitePrompt(originHost) {
        fun acknowledge() = finish(respond)

        override fun deliverDismissal() = respond()
    }

    /** JavaScript confirm() and other two-button prompts. [respond] gets null when dismissed. */
    class Confirm(
        originHost: String,
        val message: String,
        private val respond: (Boolean?) -> Unit,
    ) : SitePrompt(originHost) {
        fun accept() = finish { respond(true) }

        fun reject() = finish { respond(false) }

        override fun deliverDismissal() = respond(null)
    }

    /** "Save password?" (or "Update password?" when [isUpdate]). Never shown in private tabs. */
    class SaveLogin(
        originHost: String,
        val username: String,
        val isUpdate: Boolean,
        private val respond: (SaveLoginChoice?) -> Unit,
    ) : SitePrompt(originHost) {
        fun answer(choice: SaveLoginChoice) = finish { respond(choice) }

        override fun deliverDismissal() = respond(null)
    }

    /** Several saved logins fit this form: the user picks one. [usernames] in Gecko's order. */
    class SelectLogin(
        originHost: String,
        val usernames: List<String>,
        private val respond: (Int?) -> Unit,
    ) : SitePrompt(originHost) {
        fun choose(index: Int) = finish { respond(index) }

        override fun deliverDismissal() = respond(null)
    }

    /** JavaScript prompt(). */
    class TextInput(
        originHost: String,
        val message: String,
        val defaultValue: String,
        private val respond: (String?) -> Unit,
    ) : SitePrompt(originHost) {
        fun submit(text: String) = finish { respond(text) }

        override fun deliverDismissal() = respond(null)
    }

    /** An HTML select. [respond] gets the chosen option ids (one for single choice), or null when dismissed. */
    class Choice(
        originHost: String,
        val message: String,
        val multiple: Boolean,
        val options: List<ChoiceOption>,
        private val respond: (List<String>?) -> Unit,
    ) : SitePrompt(originHost) {
        fun submit(ids: List<String>) {
            if (ids.isEmpty()) dismiss() else finish { respond(ids) }
        }

        override fun deliverDismissal() = respond(null)
    }

    /** HTTP authentication. [passwordOnly] means the server already knows the user name. */
    class Auth(
        originHost: String,
        val message: String,
        val passwordOnly: Boolean,
        val previousFailed: Boolean,
        val username: String,
        private val respond: (AuthCredentials?) -> Unit,
    ) : SitePrompt(originHost) {
        fun submit(username: String, password: String) = finish { respond(AuthCredentials(username, password)) }

        override fun deliverDismissal() = respond(null)
    }

    /** "Leave this page?" from a beforeunload handler. */
    class BeforeUnload(
        originHost: String,
        private val respond: (Boolean?) -> Unit,
    ) : SitePrompt(originHost) {
        fun leave() = finish { respond(true) }

        fun stay() = finish { respond(false) }

        override fun deliverDismissal() = respond(null)
    }

    /** "Resend the form data?" on reload of a POST result. */
    class Repost(
        originHost: String,
        private val respond: (Boolean?) -> Unit,
    ) : SitePrompt(originHost) {
        fun resend() = finish { respond(true) }

        fun cancel() = finish { respond(false) }

        override fun deliverDismissal() = respond(null)
    }

    /** date/time-like inputs. Values are HTML strings. respond(null) = dismissed; respond("") = field cleared. */
    class DateTime(
        originHost: String,
        val kind: DateTimeKind,
        val defaultValue: String,
        val min: String,
        val max: String,
        val step: String,
        private val respond: (String?) -> Unit,
    ) : SitePrompt(originHost) {
        fun submit(value: String) = finish { respond(value) }

        fun clear() = finish { respond("") }

        override fun deliverDismissal() = respond(null)
    }

    /** `<input type=color>`. Page-suggested colours are filtered to valid "#rrggbb" by the engine. */
    class ColorPick(
        originHost: String,
        val defaultValue: String,
        val suggestions: List<String>,
        private val respond: (String?) -> Unit,
    ) : SitePrompt(originHost) {
        fun submit(value: String) = finish { respond(value) }

        override fun deliverDismissal() = respond(null)
    }

    /**
     * `<input type=file>`. Two stages: the user picks documents (or takes a photo), then the engine copies them to app
     * cache ([prepare] ends in [filesReady] or [prepareFailed]). The prompt stays queued meanwhile, so later prompts wait
     * and Gecko or the user can still cancel; dismissal stays exactly-once. [respond] gets the cached file Uris, or null
     * when dismissed.
     *
     * [route] says how it starts. Without a [camera] bridge every route is a plain document pick. The UI watches
     * [launchRequest] and launches the system document picker or camera it names; the camera route and the chooser's
     * Take picture ask for the camera permission first, and nothing is created or launched until it is granted. A photo
     * is taken into a temp [camera] makes; the temp is submitted like a picked document and [CameraBridge.release] runs
     * on every end of the prompt (never before the copy finished).
     */
    class FilePick(
        originHost: String,
        val mimeTypes: List<String>,
        val multiple: Boolean,
        private val prepare: (List<Uri>) -> Unit,
        private val respond: (List<Uri>?) -> Unit,
        route: FilePickRoute = FilePickRoute.DOCUMENTS,
        private val camera: CameraBridge? = null,
    ) : SitePrompt(originHost) {
        val route: FilePickRoute = if (camera == null) FilePickRoute.DOCUMENTS else route

        private val _stage =
            MutableStateFlow(if (this.route == FilePickRoute.CHOOSER) FilePickStage.CHOOSING else FilePickStage.PICKING)

        /** Main thread writes only. */
        val stage: StateFlow<FilePickStage> = _stage.asStateFlow()

        private val _launchRequest =
            MutableStateFlow<FilePickLaunch?>(if (this.route == FilePickRoute.DOCUMENTS) FilePickLaunch.Documents else null)

        /** The system screen the UI should open now; null while the user or the permission dialog decides. Main thread writes only. */
        val launchRequest: StateFlow<FilePickLaunch?> = _launchRequest.asStateFlow()

        private val pickerClaimed = AtomicBoolean(false)
        private val cameraStarted = AtomicBoolean(false)
        private val cameraAnswered = AtomicBoolean(false)

        // The camera output while a camera launch is outstanding (launched, no result yet); null otherwise.
        private val cameraOutstanding = AtomicReference<Uri?>(null)

        /** One launch per prompt, kept here because the composition may be recreated. True only for the first caller. */
        fun claimPickerLaunch(): Boolean = pickerClaimed.compareAndSet(false, true)

        /** Starts the camera route (the chooser waits for the user). Safe to call from every composition: it runs once. */
        fun begin() {
            if (route == FilePickRoute.CAMERA) takePicture()
        }

        /**
         * Asks for the camera permission, then publishes a camera launch. Valid once per prompt, from the chooser or the
         * camera route's start. Denied permission means nothing chosen. Nothing is created before the answer.
         */
        fun takePicture() {
            val bridge = camera ?: return
            if (isFinished || route == FilePickRoute.DOCUMENTS) return
            val start = if (route == FilePickRoute.CHOOSER) FilePickStage.CHOOSING else FilePickStage.PICKING
            if (_stage.value != start || !cameraStarted.compareAndSet(false, true)) return
            _stage.value = FilePickStage.PICKING
            try {
                bridge.ensurePermission { granted -> cameraPermissionAnswered(granted) }
            } catch (e: Exception) {
                prepareFailed()
            }
        }

        private fun cameraPermissionAnswered(granted: Boolean) {
            if (isFinished || !cameraAnswered.compareAndSet(false, true)) return
            if (!granted) {
                dismiss()
                return
            }
            val output =
                try {
                    camera?.newOutput()
                } catch (e: Exception) {
                    null
                }
            if (output == null) {
                prepareFailed()
                return
            }
            cameraOutstanding.set(output)
            _launchRequest.value = FilePickLaunch.Camera(output)
        }

        /** The chooser's Choose file: opens the document picker. */
        fun chooseDocuments() {
            if (isFinished || _stage.value != FilePickStage.CHOOSING) return
            _stage.value = FilePickStage.PICKING
            _launchRequest.value = FilePickLaunch.Documents
        }

        /**
         * The camera app's result. Ignored unless a camera launch of THIS prompt is outstanding, and only the first counts,
         * so a stray or old result cannot touch anything. Cancelled means nothing chosen; an OK without a picture (some
         * camera apps do that) is a failure.
         */
        fun cameraResult(success: Boolean) {
            if (isFinished) return
            val output = cameraOutstanding.getAndSet(null) ?: return
            if (!success) {
                dismiss()
                return
            }
            val taken =
                try {
                    camera?.hasPicture() == true
                } catch (e: Exception) {
                    false
                }
            if (taken) submit(listOf(output)) else prepareFailed()
        }

        /**
         * The camera could not be started (no camera app, permission revoked meanwhile). Once, and only while a camera
         * launch is outstanding: drops the temp and falls back to the document picker.
         */
        fun cameraLaunchFailed() {
            if (isFinished || cameraOutstanding.getAndSet(null) == null) return
            releaseCamera()
            pickerClaimed.set(false)
            _launchRequest.value = FilePickLaunch.Documents
        }

        private fun releaseCamera() {
            val bridge = camera ?: return
            runCatching { bridge.release() }
        }

        /** The user's picks. Empty means nothing chosen. Ignored once finished or past the picking stage. */
        fun submit(uris: List<Uri>) {
            if (uris.isEmpty()) {
                dismiss()
                return
            }
            if (isFinished || _stage.value != FilePickStage.PICKING) return
            _stage.value = FilePickStage.PREPARING
            try {
                prepare(uris)
            } catch (e: Exception) {
                prepareFailed()
            }
        }

        /** The copied files, ready for Gecko. After the prompt ended this does nothing. */
        fun filesReady(files: List<Uri>) {
            if (files.isEmpty()) {
                dismiss()
            } else {
                finish {
                    try {
                        respond(files)
                    } finally {
                        releaseCamera()
                    }
                }
            }
        }

        /** Copying failed: the UI shows an error and its OK calls [dismiss]. */
        fun prepareFailed() {
            if (!isFinished) _stage.value = FilePickStage.FAILED
        }

        override fun deliverDismissal() {
            try {
                respond(null)
            } finally {
                releaseCamera()
            }
        }
    }

    /**
     * Camera, microphone or location. [origin] is the requester's origin from [siteOriginOf] (punycode), never page
     * text; [embedder] is the top-level origin when the requester is a frame of another site. [respond] gets the
     * user's choice, or null when the prompt was dismissed: that means "deny, and remember nothing". [offerRemember]
     * false (private sessions) means the dialog has no "remember" choice: the answer is never stored.
     */
    class SitePermission(
        val origin: String,
        val embedder: String?,
        val kinds: List<SitePermissionKind>,
        val offerRemember: Boolean = true,
        private val respond: (SitePermissionAnswer?) -> Unit,
    ) : SitePrompt(hostOfUrl(origin).orEmpty()) {
        /** [remember] false keeps the answer for this session only. */
        fun allow(remember: Boolean) = finish { respond(SitePermissionAnswer(allow = true, remember = remember)) }

        fun deny(remember: Boolean) = finish { respond(SitePermissionAnswer(allow = false, remember = remember)) }

        override fun deliverDismissal() = respond(null)
    }
}

data class SitePermissionAnswer(val allow: Boolean, val remember: Boolean)

/** CHOOSING: the Take picture / Choose file question is showing. PICKING: a system screen (or its permission dialog) is up. */
enum class FilePickStage { CHOOSING, PICKING, PREPARING, FAILED }

/** The system screen a file prompt wants opened. */
sealed class FilePickLaunch {
    object Documents : FilePickLaunch()

    /** [output] is the content Uri the camera writes the photo to. */
    class Camera(val output: Uri) : FilePickLaunch()
}

/**
 * What a camera capture needs from the platform, so [SitePrompt.FilePick] stays free of Android calls. Main thread only.
 */
interface CameraBridge {
    /** Reports whether the camera permission is granted, asking the user if needed. Every call is answered exactly once. */
    fun ensurePermission(onResult: (Boolean) -> Unit)

    /** A fresh temp the camera can write the photo to; null when it cannot be made. */
    fun newOutput(): Uri?

    /** True when the camera wrote a photo into the temp from [newOutput]. */
    fun hasPicture(): Boolean

    /** Deletes the temp. Safe to call any number of times, and when [newOutput] was never called. */
    fun release()
}

data class AuthCredentials(val username: String, val password: String)

/** One row of a flattened select list. [depth] is 0 for top level; group headers are not selectable. */
data class ChoiceOption(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val selected: Boolean,
    val depth: Int,
    val isGroupHeader: Boolean,
)

/** Engine-neutral copy of a select item, so the flattening below is testable without engine types. */
class ChoiceNode(
    val id: String?,
    val label: String,
    val disabled: Boolean,
    val selected: Boolean,
    val separator: Boolean,
    /** Non-null for a group (optgroup), possibly empty. */
    val children: List<ChoiceNode>?,
)

/**
 * Flattens nested groups into one list with a depth. A disabled group disables everything in it. Separators and
 * items without an id (they could not be answered) are dropped.
 */
fun flattenChoices(nodes: List<ChoiceNode>): List<ChoiceOption> {
    val out = ArrayList<ChoiceOption>()
    fun walk(list: List<ChoiceNode>, depth: Int, inheritedDisabled: Boolean) {
        for (node in list) {
            if (node.separator) continue
            val disabled = inheritedDisabled || node.disabled
            val children = node.children
            if (children != null) {
                if (node.label.isNotBlank()) {
                    out += ChoiceOption("", node.label, enabled = false, selected = false, depth = depth, isGroupHeader = true)
                }
                walk(children, depth + 1, disabled)
            } else {
                val id = node.id ?: continue
                out += ChoiceOption(id, node.label, enabled = !disabled, selected = node.selected, depth = depth, isGroupHeader = false)
            }
        }
    }
    walk(nodes, 0, false)
    return out
}

/**
 * Mime types for the system document picker. Gecko may pass mime types, file extensions (".pdf") or blanks. A
 * `type/subtype` token stays; a `.ext` token maps through [extensionToMime] (lowercased, no dot); blanks are ignored.
 * If any other token cannot become a mime type the answer is "any file": dropping it would stop the user choosing the
 * very file type the page accepts. An empty result is also "any file".
 */
fun pickerMimeTypes(requested: List<String>, extensionToMime: (String) -> String? = { null }): List<String> {
    val any = listOf("*/*")
    val out = LinkedHashSet<String>()
    for (raw in requested) {
        val token = raw.trim()
        if (token.isEmpty()) continue
        val mime =
            if (token.startsWith('.')) {
                token.substring(1).lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }?.let(extensionToMime)?.trim()
            } else {
                token
            }
        if (mime == null || !isMimeType(mime)) return any
        out += mime
    }
    return if (out.isEmpty()) any else out.toList()
}

private fun isMimeType(s: String): Boolean {
    val slash = s.indexOf('/')
    return slash > 0 && slash < s.length - 1 && s.indexOf('/', slash + 1) < 0 && s.none { it.isWhitespace() }
}

/**
 * Pending prompts of ONE session, oldest first; [head] is the one being shown. Main thread only.
 * A prompt leaves the queue when it finishes by any route (answered, dismissed, [dismissAll]).
 */
class PromptQueue {
    private val queue = ArrayDeque<SitePrompt>()
    private val _head = MutableStateFlow<SitePrompt?>(null)
    val head: StateFlow<SitePrompt?> = _head.asStateFlow()

    fun add(prompt: SitePrompt) {
        if (prompt.isFinished) return
        prompt.onFinished = { remove(it) }
        queue.addLast(prompt)
        publish()
    }

    /** Completes every pending prompt as dismissed (session close, crash, kill). */
    fun dismissAll() {
        for (prompt in queue.toList()) prompt.dismiss()
    }

    /** Dismisses every pending prompt [predicate] selects (for example those of a page that is gone). */
    fun dismissWhere(predicate: (SitePrompt) -> Boolean) {
        for (prompt in queue.toList()) if (predicate(prompt)) prompt.dismiss()
    }

    val size: Int get() = queue.size

    private fun remove(prompt: SitePrompt) {
        queue.remove(prompt)
        publish()
    }

    private fun publish() {
        _head.value = queue.firstOrNull()
    }
}

/** The answer to a [SitePrompt.SaveLogin]. */
enum class SaveLoginChoice { SAVE, NEVER, NOT_NOW }

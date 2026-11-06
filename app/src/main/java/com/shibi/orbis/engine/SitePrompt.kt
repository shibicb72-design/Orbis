package com.shibi.orbis.engine

import android.net.Uri
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
     * `<input type=file>`. Capture (camera) requests are ignored; the result is a plain document pick.
     * Two stages: the user picks documents, then the engine copies them to app cache ([prepare] ends in [filesReady]
     * or [prepareFailed]). The prompt stays queued meanwhile, so later prompts wait and Gecko or the user can still
     * cancel; dismissal stays exactly-once. [respond] gets the cached file Uris, or null when dismissed.
     */
    class FilePick(
        originHost: String,
        val mimeTypes: List<String>,
        val multiple: Boolean,
        private val prepare: (List<Uri>) -> Unit,
        private val respond: (List<Uri>?) -> Unit,
    ) : SitePrompt(originHost) {
        private val _stage = MutableStateFlow(FilePickStage.PICKING)

        /** Main thread writes only. */
        val stage: StateFlow<FilePickStage> = _stage.asStateFlow()

        private val pickerClaimed = AtomicBoolean(false)

        /** One launch per prompt, kept here because the composition may be recreated. True only for the first caller. */
        fun claimPickerLaunch(): Boolean = pickerClaimed.compareAndSet(false, true)

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
            if (files.isEmpty()) dismiss() else finish { respond(files) }
        }

        /** Copying failed: the UI shows an error and its OK calls [dismiss]. */
        fun prepareFailed() {
            if (!isFinished) _stage.value = FilePickStage.FAILED
        }

        override fun deliverDismissal() = respond(null)
    }

    /**
     * Camera, microphone or location. [origin] is the requester's origin from [siteOriginOf] (punycode), never page
     * text; [embedder] is the top-level origin when the requester is a frame of another site. [respond] gets the
     * user's choice, or null when the prompt was dismissed: that means "deny, and remember nothing".
     */
    class SitePermission(
        val origin: String,
        val embedder: String?,
        val kinds: List<SitePermissionKind>,
        private val respond: (SitePermissionAnswer?) -> Unit,
    ) : SitePrompt(hostOfUrl(origin).orEmpty()) {
        /** [remember] false keeps the answer for this session only. */
        fun allow(remember: Boolean) = finish { respond(SitePermissionAnswer(allow = true, remember = remember)) }

        fun deny(remember: Boolean) = finish { respond(SitePermissionAnswer(allow = false, remember = remember)) }

        override fun deliverDismissal() = respond(null)
    }
}

data class SitePermissionAnswer(val allow: Boolean, val remember: Boolean)

enum class FilePickStage { PICKING, PREPARING, FAILED }

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

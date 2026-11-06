package com.shibi.orbis.engine

import android.net.Uri
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

    /** `<input type=file>`. Capture (camera) requests are ignored; the result is a plain document pick. */
    class FilePick(
        originHost: String,
        val mimeTypes: List<String>,
        val multiple: Boolean,
        private val respond: (List<Uri>?) -> Unit,
    ) : SitePrompt(originHost) {
        fun submit(uris: List<Uri>) {
            if (uris.isEmpty()) dismiss() else finish { respond(uris) }
        }

        override fun deliverDismissal() = respond(null)
    }
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
 * Mime types for the system document picker. Gecko may pass file extensions (".pdf") or blanks, which the picker
 * does not understand; anything without a slash is dropped, and an empty result means "any file".
 */
fun pickerMimeTypes(requested: List<String>): List<String> {
    val usable = requested.map { it.trim() }.filter { it.contains('/') && !it.contains(' ') }.distinct()
    return usable.ifEmpty { listOf("*/*") }
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

    val size: Int get() = queue.size

    private fun remove(prompt: SitePrompt) {
        queue.remove(prompt)
        publish()
    }

    private fun publish() {
        _head.value = queue.firstOrNull()
    }
}

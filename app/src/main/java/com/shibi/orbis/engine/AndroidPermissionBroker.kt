package com.shibi.orbis.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Remembers which Android permissions the system dialog has already been shown for. Backed by SharedPreferences. */
interface AskedPermissions {
    fun wasAsked(permission: String): Boolean

    fun markAsked(permission: String)
}

/**
 * One system permission dialog the UI should launch. [permissions] are in a fixed order and never empty. Identical
 * requests share one instance (their callers wait on it together).
 */
class AndroidPermissionRequest internal constructor(val id: Long, val permissions: List<String>) {
    internal val callbacks = ArrayList<(Boolean) -> Unit>()

    private val launchClaimed = AtomicBoolean(false)

    /** One system launch per request, kept here because the composition may be recreated. True only for the first caller. */
    fun claimLaunch(): Boolean = launchClaimed.compareAndSet(false, true)
}

/**
 * Turns Gecko's Android-permission questions into at most one system dialog at a time. App level, one instance:
 * Gecko routes these requests to its most recent window, which can be a background tab, so they must not go through a
 * per-session prompt queue. Main thread only; no Android types, so it is unit tested.
 *
 * Every [request] callback runs exactly once: granted, denied, refused without a dialog (not an allowed permission, or
 * inside the denial cool-down) or failed to launch.
 *
 * "Blocked" (Android will no longer show the dialog) is only ever judged AFTER a system request, never before: the
 * request is always launched, and a result is blocked only when it was denied, the system says no rationale, and we had
 * asked before. A pre-check would mistake an expired "Only this time" grant for a block.
 */
class AndroidPermissionBroker(
    private val asked: AskedPermissions,
    private val now: () -> Long,
) {
    private val pending = ArrayList<AndroidPermissionRequest>()
    private val deniedAt = HashMap<String, Long>()
    private var nextId = 1L

    private val _head = MutableStateFlow<AndroidPermissionRequest?>(null)

    /** The request the UI should launch next; null when idle. */
    val head: StateFlow<AndroidPermissionRequest?> = _head.asStateFlow()

    private val _settingsHint = MutableStateFlow(false)

    /** True after Android blocked further dialogs for something that was needed: offer the app settings page. */
    val settingsHint: StateFlow<Boolean> = _settingsHint.asStateFlow()

    /**
     * Asks for [permissions] and reports whether they ended up granted (a location request counts as granted when fine
     * OR coarse is). A location request always asks fine and coarse together: with targetSdk 31+ the system ignores a
     * fine-only request.
     */
    fun request(permissions: Collection<String>, onResult: (Boolean) -> Unit) {
        val wanted = normalize(permissions)
        if (wanted == null || wanted.any { inCooldown(it) }) {
            deliver(onResult, false)
            return
        }
        val existing = pending.firstOrNull { it.permissions == wanted }
        if (existing != null) {
            existing.callbacks += onResult
            return
        }
        val request = AndroidPermissionRequest(nextId++, wanted)
        request.callbacks += onResult
        pending += request
        publish()
    }

    /**
     * [request] unless [permissions] are already granted per [isGranted]: then [onResult] gets true at once, with no
     * dialog and nothing queued. For callers that act on the user's own behalf (a camera capture) and so need the
     * permission before they start.
     */
    fun ensure(permissions: Collection<String>, isGranted: (String) -> Boolean, onResult: (Boolean) -> Unit) {
        if (isSatisfied(permissions, isGranted)) {
            deliver(onResult, true)
            return
        }
        request(permissions, onResult)
    }

    /**
     * The UI's report of a system result. [blocked] is already judged by the caller (see [evaluate]). A request that was
     * already finished is ignored.
     */
    fun finish(request: AndroidPermissionRequest, results: Map<String, Boolean>, blocked: Boolean) {
        if (!pending.remove(request)) return
        val granted = isSatisfied(request.permissions) { results[it] == true }
        val t = now()
        for (p in request.permissions) if (results[p] == true) deniedAt.remove(p)
        if (!granted) {
            for (p in unsatisfied(request.permissions, results)) deniedAt[p] = t
            if (blocked) _settingsHint.value = true
        }
        // Requests still queued behind it that need something just denied must not nag the user again.
        val refused = pending.filter { queued -> queued.permissions.any { inCooldown(it) } }
        pending.removeAll(refused.toSet())
        publish()
        for (cb in request.callbacks) deliver(cb, granted)
        for (r in refused) for (cb in r.callbacks) deliver(cb, false)
    }

    /**
     * [finish] for a raw system result: judges "blocked" (denied, no rationale, asked before), then remembers that the
     * dialog has now been shown. [shouldShowRationale] is Activity.shouldShowRequestPermissionRationale.
     */
    fun evaluate(request: AndroidPermissionRequest, results: Map<String, Boolean>, shouldShowRationale: (String) -> Boolean) {
        if (request !in pending) return
        val blocked = request.permissions.any { p -> results[p] != true && !shouldShowRationale(p) && asked.wasAsked(p) }
        for (p in request.permissions) asked.markAsked(p)
        finish(request, results, blocked)
    }

    /** The system dialog could not be launched: complete as denied, and never as blocked. */
    fun failLaunch(request: AndroidPermissionRequest) = finish(request, emptyMap(), blocked = false)

    fun dismissSettingsHint() {
        _settingsHint.value = false
    }

    private fun inCooldown(permission: String): Boolean {
        val at = deniedAt[permission] ?: return false
        val elapsed = now() - at
        // A clock that went backwards must not lock a permission out for long.
        return elapsed in 0 until DENIAL_COOLDOWN_MS
    }

    private fun publish() {
        _head.value = pending.firstOrNull()
    }

    private fun deliver(callback: (Boolean) -> Unit, granted: Boolean) {
        runCatching { callback(granted) }
    }

    companion object {
        const val CAMERA = "android.permission.CAMERA"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
        const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

        /** The only permissions this browser ever requests; the order is the order requests list them in. */
        val ALLOWED: List<String> = listOf(CAMERA, RECORD_AUDIO, FINE_LOCATION, COARSE_LOCATION)

        const val DENIAL_COOLDOWN_MS = 60_000L

        private val LOCATION = listOf(FINE_LOCATION, COARSE_LOCATION)

        /**
         * True when everything in [permissions] is granted per [isGranted], where the location pair counts as granted
         * if either of them is (the user may have chosen Approximate). Empty is false.
         */
        fun isSatisfied(permissions: Collection<String>, isGranted: (String) -> Boolean): Boolean {
            if (permissions.isEmpty()) return false
            // Asking for either location permission is asking for the pair: either one granted is enough.
            val locationOk = permissions.none { it in LOCATION } || LOCATION.any(isGranted)
            return locationOk && permissions.filter { it !in LOCATION }.all(isGranted)
        }

        /** Allowed, de-duplicated, location expanded to fine plus coarse, in [ALLOWED] order; null if refused. */
        internal fun normalize(permissions: Collection<String>): List<String>? {
            if (permissions.isEmpty() || permissions.any { it !in ALLOWED }) return null
            val set = permissions.toMutableSet()
            if (set.any { it in LOCATION }) set.addAll(LOCATION)
            return ALLOWED.filter { it in set }
        }

        private fun unsatisfied(permissions: List<String>, results: Map<String, Boolean>): List<String> {
            val locationOk = isSatisfied(permissions.filter { it in LOCATION }) { results[it] == true }
            return permissions.filter { p ->
                if (p in LOCATION) !locationOk else results[p] != true
            }
        }
    }
}

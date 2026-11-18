package com.shibi.orbis.engine

import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Engine-neutral WebExtension management. Call from the main thread. */
interface ExtensionHost {
    /**
     * The toolbar buttons of the installed add-ons for the tab given to [selectSession], with that tab's own values
     * where the add-on set them. Orbis's built-in extensions never appear; see [shownAddonActions] for the other rules.
     * Empty while no tab is selected.
     */
    val actions: StateFlow<List<AddonAction>> get() = NO_ADDON_ACTIONS

    /**
     * The tab whose add-on buttons [actions] lists and [clickAction] acts on; null for none (a page hidden by the
     * private-tab lock passes null). Selecting another tab closes the open [popup].
     */
    fun selectSession(session: BrowserSession?) {}

    /**
     * Taps add-on [extensionId]'s button for the selected tab. Does nothing when it is not in [actions], is disabled, or
     * no tab is selected. If the add-on has a panel it appears in [popup]; tapping again closes it.
     */
    fun clickAction(extensionId: String) {}

    /** The add-on panel that is open now, or null. Only a [clickAction] opens one; an add-on cannot open it on its own. */
    val popup: StateFlow<AddonPopup?> get() = NO_ADDON_POPUP


    suspend fun find(id: String): InstalledExtension?

    /** Every installed extension, Orbis's own built-in ones included. */
    suspend fun list(): List<InstalledExtension> = throw UnsupportedOperationException("list")

    /**
     * Installs [xpiUrl]; the result must have id [expectedId] and a Mozilla (AMO) signature. Grants the install prompt
     * without asking and allows private tabs with it (uBlock Origin's path).
     */
    suspend fun install(xpiUrl: String, expectedId: String): InstalledExtension

    /**
     * Installs [xpiUrl] like [install], but the engine's permission prompt is granted only when the extension's id is
     * [expectedId] AND [confirm] (shown the access the engine reports) returns true. A false answer fails with
     * [ExtensionInstallError.CANCELLED]. Private tabs are NOT allowed at install. Data collection is never granted.
     * An add-on the engine flags (signature or blocklist) is refused without asking.
     */
    suspend fun install(
        xpiUrl: String,
        expectedId: String,
        confirm: suspend (AddonAccess) -> Boolean,
    ): InstalledExtension = throw UnsupportedOperationException("install with confirm")

    suspend fun setEnabled(id: String, enabled: Boolean): InstalledExtension

    /**
     * Lets extension [id] run in private tabs. Does nothing when it is missing or already allowed (the engine restarts
     * the extension on every call, so repeating it is not free). Never enables an extension the user disabled.
     */
    suspend fun allowInPrivateBrowsing(id: String)

    /** Allows or stops extension [id] in private tabs; fails when it is not installed. A no-op when already so. */
    suspend fun setAllowedInPrivateBrowsing(id: String, allowed: Boolean): InstalledExtension =
        throw UnsupportedOperationException("setAllowedInPrivateBrowsing")

    suspend fun uninstall(id: String)

    /**
     * Returns the updated extension, or null when no update was available. Throws [ExtensionInstallException] when the
     * engine refused the update; [ExtensionInstallError.CANCELLED] means it asks for more access than was agreed to.
     */
    suspend fun checkForUpdate(id: String): InstalledExtension?
}

/**
 * One add-on's toolbar button. [title] is the add-on's name (the button's own title when the engine has no name).
 * [badgeText] is null when the add-on shows no badge.
 */
data class AddonAction(
    val extensionId: String,
    val title: String,
    val enabled: Boolean,
    val badgeText: String?,
)

/**
 * An open add-on panel. The view passed to [attachTo] must come from [BrowserEngine.createContentView]. [close] ends
 * the panel's page; it is safe to call more than once.
 */
interface AddonPopup {
    /** The add-on that owns the panel. */
    val extensionId: String

    /** The add-on's name, for the panel's title. */
    val title: String

    fun attachTo(view: View)

    fun detachFrom(view: View)

    fun close()
}

private val NO_ADDON_ACTIONS: StateFlow<List<AddonAction>> = MutableStateFlow(emptyList<AddonAction>()).asStateFlow()
private val NO_ADDON_POPUP: StateFlow<AddonPopup?> = MutableStateFlow<AddonPopup?>(null).asStateFlow()

/** Orbis's own extensions (assets/extensions). They are part of the browser, not add-ons, and have no button. */
internal val BUILT_IN_EXTENSION_IDS: Set<String> =
    setOf(
        ReaderExtensionHolder.ID,
        CastExtensionHolder.ID,
        CompatExtensionHolder.ID,
        DARKEN_ID,
        YoutubeAdExtensionHolder.ID,
        YoutubePipExtension.ID,
        CLEAR_EXIT_ID,
    )

internal fun isBuiltInExtension(id: String): Boolean = id in BUILT_IN_EXTENSION_IDS || id.endsWith("@orbis.shibi.com")

/**
 * The buttons the "Add-ons" list shows, sorted by name: not Orbis's built-in extensions, only add-ons that are
 * installed and on (an add-on off for any reason has no entry), and in a private tab only those allowed in private
 * tabs.
 */
internal fun shownAddonActions(
    actions: List<AddonAction>,
    installed: List<InstalledExtension>,
    privateTab: Boolean,
): List<AddonAction> {
    val byId = installed.associateBy { it.id }
    return actions
        .filter { action ->
            val extension = byId[action.extensionId]
            !isBuiltInExtension(action.extensionId) &&
                extension != null &&
                extension.enabled &&
                extension.disabledReason == null &&
                (!privateTab || extension.allowedInPrivate)
        }
        .sortedBy { it.title.lowercase() }
}

/** What the engine reports an extension asks for in its install prompt (data-collection permissions not included). */
data class AddonAccess(
    val permissions: List<String>,
    val origins: List<String>,
)

data class InstalledExtension(
    val id: String,
    val version: String,
    val enabled: Boolean,
    /** True when the user (not the browser, blocklist or signature check) disabled it. */
    val disabledByUser: Boolean,
    /** Why it is off, from the engine's flags; null when it is enabled. */
    val disabledReason: ExtensionDisabledReason? = null,
    /** The extension's own settings page (moz-extension://...), or null when it has none. */
    val optionsPageUrl: String? = null,
    val name: String? = null,
    val allowedInPrivate: Boolean = false,
    /** Required API permissions (manifest), as the engine reports them. */
    val permissions: List<String> = emptyList(),
    /** Required host permissions (origins), as the engine reports them. */
    val origins: List<String> = emptyList(),
)

/** Why the engine has an extension switched off. Precedence when several apply: BLOCKLIST, SIGNATURE, USER, OTHER. */
enum class ExtensionDisabledReason {
    USER,
    BLOCKLIST,
    SIGNATURE,
    OTHER,
}

enum class ExtensionInstallError {
    NETWORK,
    SIGNATURE,
    INCOMPATIBLE,
    BLOCKED,
    CORRUPT,
    WRONG_EXTENSION,
    CANCELLED,
    UNKNOWN,
}

class ExtensionInstallException(
    val error: ExtensionInstallError,
    message: String? = null,
) : Exception(message ?: "extension install failed: $error")

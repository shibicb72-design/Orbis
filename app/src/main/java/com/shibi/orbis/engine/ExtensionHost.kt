package com.shibi.orbis.engine

/** Engine-neutral WebExtension management. Call from the main thread. */
interface ExtensionHost {
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

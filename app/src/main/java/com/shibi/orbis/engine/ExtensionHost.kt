package com.shibi.orbis.engine

/** Engine-neutral WebExtension management. Call from the main thread. */
interface ExtensionHost {
    suspend fun find(id: String): InstalledExtension?

    /** Installs [xpiUrl]; the result must have id [expectedId] and a Mozilla (AMO) signature. */
    suspend fun install(xpiUrl: String, expectedId: String): InstalledExtension

    suspend fun setEnabled(id: String, enabled: Boolean): InstalledExtension

    /**
     * Lets extension [id] run in private tabs. Does nothing when it is missing or already allowed (the engine restarts
     * the extension on every call, so repeating it is not free). Never enables an extension the user disabled.
     */
    suspend fun allowInPrivateBrowsing(id: String)

    suspend fun uninstall(id: String)

    /** Returns the updated extension, or null when no update was available. */
    suspend fun checkForUpdate(id: String): InstalledExtension?
}

data class InstalledExtension(
    val id: String,
    val version: String,
    val enabled: Boolean,
    /** True when the user (not the browser, blocklist or signature check) disabled it. */
    val disabledByUser: Boolean,
)

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

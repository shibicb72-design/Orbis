package com.shibi.orbis.ubo

import com.shibi.orbis.engine.ExtensionInstallError
import com.shibi.orbis.engine.InstalledExtension

const val UBO_ID = "uBlock0@raymondhill.net"
const val UBO_XPI_URL = "https://addons.mozilla.org/firefox/downloads/latest/uBlock0@raymondhill.net/latest.xpi"

enum class UboDecision { UNDECIDED, ACCEPTED, DECLINED }

/** Persisted onboarding choice and the time of the last successful update check. */
interface UboPrefs {
    var decision: UboDecision
    var lastUpdateCheckMillis: Long
}

enum class UboFailure {
    NETWORK,
    SIGNATURE,
    INCOMPATIBLE,
    BLOCKED,
    CORRUPT,
    WRONG_EXTENSION,
    CANCELLED,
    UNKNOWN,
    TIMEOUT,
}

sealed interface UboState {
    data object Checking : UboState
    data object NotInstalled : UboState
    data object Installing : UboState
    data class Installed(
        val version: String,
        val enabled: Boolean,
        val disabledByUser: Boolean,
    ) : UboState
    data class Failed(val reason: UboFailure) : UboState
}

internal fun InstalledExtension.toUboState() = UboState.Installed(version, enabled, disabledByUser)

internal fun ExtensionInstallError.toUboFailure(): UboFailure =
    when (this) {
        ExtensionInstallError.NETWORK -> UboFailure.NETWORK
        ExtensionInstallError.SIGNATURE -> UboFailure.SIGNATURE
        ExtensionInstallError.INCOMPATIBLE -> UboFailure.INCOMPATIBLE
        ExtensionInstallError.BLOCKED -> UboFailure.BLOCKED
        ExtensionInstallError.CORRUPT -> UboFailure.CORRUPT
        ExtensionInstallError.WRONG_EXTENSION -> UboFailure.WRONG_EXTENSION
        ExtensionInstallError.CANCELLED -> UboFailure.CANCELLED
        ExtensionInstallError.UNKNOWN -> UboFailure.UNKNOWN
    }

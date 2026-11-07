package com.shibi.orbis.privacy

/** What the window should do for the current tabs; applied by MainActivity. */
data class PrivacyWindowDecision(
    /** Whether the system may take a Recents (task switcher) screenshot. Only applied on API 33+. */
    val recentsScreenshotEnabled: Boolean,
    /** Whether the window carries FLAG_SECURE. Only requested below API 33, where the Recents switch does not exist. */
    val flagSecure: Boolean,
)

/**
 * Keeps private tabs out of the task switcher. The tab switcher lists private tabs next to normal ones, so the window is
 * protected while ANY private tab is open, not only while one is selected. On API 33+ the Recents screenshot is switched
 * off; below that the only tool is FLAG_SECURE, which also blocks user screenshots and screen recording of the window for
 * as long as any private tab is open. With no private tab open both are restored. Pure, so the decision is testable
 * without a device.
 */
object PrivacyWindowPolicy {
    /** First API with Activity.setRecentsScreenshotEnabled (Android 13). */
    const val RECENTS_API = 33

    fun decide(apiLevel: Int, anyPrivateTabOpen: Boolean): PrivacyWindowDecision =
        when {
            !anyPrivateTabOpen -> PrivacyWindowDecision(recentsScreenshotEnabled = true, flagSecure = false)
            apiLevel >= RECENTS_API -> PrivacyWindowDecision(recentsScreenshotEnabled = false, flagSecure = false)
            else -> PrivacyWindowDecision(recentsScreenshotEnabled = true, flagSecure = true)
        }
}

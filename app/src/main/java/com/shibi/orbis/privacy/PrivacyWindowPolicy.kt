package com.shibi.orbis.privacy

/** What the window should do for the current tabs; applied by MainActivity. */
data class PrivacyWindowDecision(
    /** Whether the system may take a Recents (task switcher) screenshot. Only applied on API 33+. */
    val recentsScreenshotEnabled: Boolean,
    /**
     * Whether the window carries FLAG_SECURE. True whenever any private tab is open, on every API level: on an API 35
     * emulator setRecentsScreenshotEnabled(false) alone still showed the live private tab in Recents. Side effect: user
     * screenshots and screen recording of the app are blocked while a private tab is open.
     */
    val flagSecure: Boolean,
)

/** The window operations the controller drives; MainActivity implements them, tests fake them. */
interface PrivacyWindowOps {
    /** Activity.setRecentsScreenshotEnabled; called only on API 33+. */
    fun setRecentsScreenshot(enabled: Boolean)

    /** Adds ([secure] true) or clears FLAG_SECURE on the window. */
    fun setFlagSecure(secure: Boolean)
}

/** Turns [PrivacyWindowPolicy] decisions into window operations. */
class PrivacyWindowController(private val ops: PrivacyWindowOps) {
    fun apply(apiLevel: Int, anyPrivateTabOpen: Boolean, sensitiveScreenOpen: Boolean = false) {
        val decision = PrivacyWindowPolicy.decide(apiLevel, anyPrivateTabOpen, sensitiveScreenOpen)
        if (apiLevel >= PrivacyWindowPolicy.RECENTS_API) ops.setRecentsScreenshot(decision.recentsScreenshotEnabled)
        ops.setFlagSecure(decision.flagSecure)
    }
}

/**
 * Keeps private tabs out of the task switcher. The tab switcher lists private tabs next to normal ones, so the window is
 * protected while ANY private tab is open, not only while one is selected. While one is open, FLAG_SECURE is set on every
 * API level (like Chrome Incognito and Firefox private browsing), and on API 33+ the Recents screenshot is also switched
 * off. The Recents switch alone is not enough: on an API 35 emulator the Recents card still showed the live private tab.
 * Trade-off: user screenshots and screen recording of the app are blocked for as long as any private tab is open. With no
 * private tab open both are restored. Pure, so the decision is testable without a device.
 */
object PrivacyWindowPolicy {
    /** First API with Activity.setRecentsScreenshotEnabled (Android 13); kept as a belt-and-braces call next to FLAG_SECURE. */
    const val RECENTS_API = 33

    /**
     * [sensitiveScreenOpen]: a screen that shows secrets (Settings > Passwords) is on screen. It gets the same protection
     * as a private tab, so a revealed password never lands in the Recents card or a screenshot.
     */
    @Suppress("UNUSED_PARAMETER")
    fun decide(apiLevel: Int, anyPrivateTabOpen: Boolean, sensitiveScreenOpen: Boolean = false): PrivacyWindowDecision {
        val protect = anyPrivateTabOpen || sensitiveScreenOpen
        return PrivacyWindowDecision(recentsScreenshotEnabled = !protect, flagSecure = protect)
    }
}

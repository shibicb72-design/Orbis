package com.shibi.orbis.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonActionsTest {
    private fun action(id: String, title: String = id) = AddonAction(id, title, enabled = true, badgeText = null)

    private fun installed(
        id: String,
        enabled: Boolean = true,
        disabledReason: ExtensionDisabledReason? = null,
        allowedInPrivate: Boolean = false,
    ) = InstalledExtension(
        id = id,
        version = "1",
        enabled = enabled,
        disabledByUser = disabledReason == ExtensionDisabledReason.USER,
        disabledReason = disabledReason,
        allowedInPrivate = allowedInPrivate,
    )

    @Test(timeout = 10_000)
    fun `an action from a built-in extension id is filtered out`() {
        val ids = listOf(ReaderExtensionHolder.ID, DARKEN_ID, CLEAR_EXIT_ID, "addon@darkreader.org")
        val shown =
            shownAddonActions(
                actions = ids.map { action(it) },
                installed = ids.map { installed(it, allowedInPrivate = true) },
                privateTab = false,
            )
        assertEquals(listOf("addon@darkreader.org"), shown.map { it.extensionId })
    }

    @Test(timeout = 10_000)
    fun `every built-in extension is filtered out`() {
        val ids = BUILT_IN_EXTENSION_IDS.toList()
        assertEquals(7, ids.size)
        val shown = shownAddonActions(ids.map { action(it) }, ids.map { installed(it) }, privateTab = false)
        assertEquals(emptyList<AddonAction>(), shown)
    }

    @Test(timeout = 10_000)
    fun `an action for a private tab is dropped when the add-on is not allowed in private tabs`() {
        val actions = listOf(action("sponsorBlocker@ajay.app", "SponsorBlock"), action("addon@darkreader.org", "Dark Reader"))
        val list = listOf(installed("sponsorBlocker@ajay.app", allowedInPrivate = false), installed("addon@darkreader.org", allowedInPrivate = true))

        assertEquals(listOf("addon@darkreader.org"), shownAddonActions(actions, list, privateTab = true).map { it.extensionId })
        // The same add-ons in a normal tab: both, by name.
        assertEquals(listOf("Dark Reader", "SponsorBlock"), shownAddonActions(actions, list, privateTab = false).map { it.title })
    }

    @Test(timeout = 10_000)
    fun `an add-on that is off for any reason or not installed has no entry`() {
        val ids = listOf("user@x", "blocked@x", "signature@x", "other@x", "missing@x", "on@x")
        val list =
            listOf(
                installed("user@x", enabled = false, disabledReason = ExtensionDisabledReason.USER),
                installed("blocked@x", enabled = false, disabledReason = ExtensionDisabledReason.BLOCKLIST),
                installed("signature@x", enabled = false, disabledReason = ExtensionDisabledReason.SIGNATURE),
                installed("other@x", enabled = false, disabledReason = ExtensionDisabledReason.OTHER),
                installed("on@x"),
            )
        assertEquals(listOf("on@x"), shownAddonActions(ids.map { action(it) }, list, privateTab = false).map { it.extensionId })
    }

    @Test(timeout = 10_000)
    fun `uBlock Origin's button is listed`() {
        val ubo = "uBlock0@raymondhill.net"
        val shown = shownAddonActions(listOf(action(ubo, "uBlock Origin")), listOf(installed(ubo, allowedInPrivate = true)), privateTab = true)
        assertEquals(listOf(ubo), shown.map { it.extensionId })
    }
}

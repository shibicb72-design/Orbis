package com.shibi.orbis.addons

import androidx.annotation.StringRes

/**
 * One add-on Orbis offers. [xpiUrl] is Mozilla's "latest signed version" address for [id] (each checked with a HEAD
 * request on 2026-11-17: 200, application/x-xpinstall). [accessSummary] is the one-line access summary for the list row;
 * the install dialog shows the full list the engine reports.
 */
data class CatalogAddon(
    val id: String,
    val xpiUrl: String,
    val name: String,
    @StringRes val summaryRes: Int? = null,
    val accessSummary: String,
    /** Listed last, under "Advanced", with a warning. */
    val advanced: Boolean = false,
)

/**
 * The fixed list of add-ons Orbis can install (Shibi's ruling, feat08 plan). There is no network lookup of this list,
 * and no other id can be installed through [AddonManager]. uBlock Origin is not here: it keeps its own flow (`ubo/`).
 */
object AddonCatalog {
    private const val AMO_LATEST = "https://addons.mozilla.org/firefox/downloads/latest/"

    val entries: List<CatalogAddon> =
        listOf(
            CatalogAddon(
                id = "sponsorBlocker@ajay.app",
                xpiUrl = AMO_LATEST + "sponsorBlocker@ajay.app/latest.xpi",
                name = "SponsorBlock",
                accessSummary = "YouTube pages only; its own server sponsor.ajay.app; storage",
            ),
            CatalogAddon(
                id = "gdpr@cavi.au.dk",
                xpiUrl = AMO_LATEST + "gdpr@cavi.au.dk/latest.xpi",
                name = "Consent-O-Matic",
                accessSummary = "Every page; tabs; storage",
            ),
            CatalogAddon(
                id = "{74145f27-f039-47ce-a470-a662b129930a}",
                xpiUrl = AMO_LATEST + "%7B74145f27-f039-47ce-a470-a662b129930a%7D/latest.xpi",
                name = "ClearURLs",
                accessSummary = "Every page; web requests (can block and rewrite); tabs; downloads; storage",
            ),
            CatalogAddon(
                id = "addon@darkreader.org",
                xpiUrl = AMO_LATEST + "addon@darkreader.org/latest.xpi",
                name = "Dark Reader",
                accessSummary = "Every page; tabs; storage; alarms",
            ),
            CatalogAddon(
                id = "{aecec67f-0d10-4fa7-b7c7-609a2db280cf}",
                xpiUrl = AMO_LATEST + "%7Baecec67f-0d10-4fa7-b7c7-609a2db280cf%7D/latest.xpi",
                name = "Violentmonkey",
                accessSummary =
                    "Every page; web requests (can block); cookies; clipboard write; notifications; storage",
                advanced = true,
            ),
        )

    fun find(id: String): CatalogAddon? = entries.firstOrNull { it.id == id }
}

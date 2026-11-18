package com.shibi.orbis.backup

import com.shibi.orbis.engine.SitePermissionDecision
import com.shibi.orbis.engine.SitePermissionKey
import com.shibi.orbis.engine.SitePermissionKind
import com.shibi.orbis.home.PinnedSite
import com.shibi.orbis.passwords.ImportedLogin
import com.shibi.orbis.settings.CUSTOMIZABLE_MENU_ACTIONS
import com.shibi.orbis.tabs.PersistedTab
import com.shibi.orbis.tabs.TabGroup
import com.shibi.orbis.tabs.TabGroupColor
import com.shibi.orbis.ui.browser.MenuAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupContentsTest {
    /** Encodes, then decodes from the JSON text, as a restore reads it. */
    private fun <T> roundTrip(encoded: JSONObject, decode: (JSONObject) -> SectionRead<T>): SectionRead.Ok<T> =
        decode(JSONObject(encoded.toString())) as SectionRead.Ok<T>

    @Test(timeout = 10_000)
    fun bookmarksKeepTheirFoldersAndDropUnusableEntries() {
        val items =
            listOf(
                BackupBookmark("https://a.example/", "A", 10L, folder = "Work"),
                BackupBookmark("https://b.example/", "B", 20L),
            )
        val back = roundTrip(BookmarksSection.encode(items), BookmarksSection::decode)
        assertEquals(items, back.value)
        assertEquals(0, back.skipped)

        val raw = BookmarksSection.encode(items)
        raw.getJSONArray("items")
            .put(JSONObject().put("url", "javascript:alert(1)").put("title", "x"))
            .put(JSONObject().put("url", "https://a.example/").put("title", "again"))
        val read = BookmarksSection.decode(raw) as SectionRead.Ok
        assertEquals(listOf("https://a.example/", "https://b.example/"), read.value.map { it.url })
        assertEquals(2, read.skipped)
    }

    @Test(timeout = 10_000)
    fun passwordsNeedAWebOriginAndAPassword() {
        val good = ImportedLogin("https://site.example", "me", "pw,\"with\"\nodd chars", "", "")
        val raw = PasswordsSection.encode(listOf(good))
        raw.getJSONArray("items")
            .put(JSONObject().put("origin", "ftp://x.example").put("password", "p"))
            .put(JSONObject().put("origin", "https://y.example").put("password", ""))
        val read = roundTrip(raw, PasswordsSection::decode)
        assertEquals(listOf(good), read.value)
        assertEquals(2, read.skipped)
    }

    @Test(timeout = 10_000)
    fun settingsEncodeWritesOnlyAllowListedKeys() {
        // What a raw dump of the preference files would hold: allow-listed keys and everything else.
        val raw: SettingsValues =
            mapOf(
                "browser_prefs" to
                    mapOf(
                        "https_only" to "PRIVATE",
                        "text_zoom_percent" to 125,
                        "torrent_ip_note_seen" to true,
                        "hidden_sites" to setOf("x.example"),
                        "javascript_on_sites" to setOf("x.example"),
                    ),
                "addon_prefs" to mapOf("last_check_sponsorBlocker@ajay.app" to 5),
                "ubo_prefs" to mapOf("decision" to "ACCEPTED"),
                "doh_prefs" to mapOf("enabled" to true, "custom_url" to "http://not-https.example/"),
            )
        val files = SettingsSection.encode(raw).getJSONObject("files")
        val written = files.keys().asSequence().associateWith { f -> files.getJSONObject(f).keys().asSequence().toSet() }
        assertEquals(mapOf("browser_prefs" to setOf("https_only", "text_zoom_percent"), "doh_prefs" to setOf("enabled")), written)
        for ((file, keys) in written) {
            assertTrue(file, file in SettingsAllowList.files)
            assertTrue(keys.toString(), SettingsAllowList.files.getValue(file).keys.containsAll(keys))
        }
    }

    @Test(timeout = 10_000)
    fun settingsDecodeSkipsUnknownKeysFilesAndInvalidValues() {
        val files =
            JSONObject()
                .put(
                    "browser_prefs",
                    JSONObject()
                        .put("https_only", "PRIVATE")
                        .put("text_zoom_percent", 999)
                        .put("reader_theme", "NEON")
                        .put("homepage", "javascript:alert(1)")
                        .put("torrent_ip_note_seen", true)
                        .put("force_dark", "yes"),
                )
                .put("cookies", JSONObject().put("sid", "secret"))
                .put("doh_prefs", JSONObject().put("custom_url", "https://user:pw@dns.example/q").put("provider", "MULLVAD"))
        val read = SettingsSection.decode(JSONObject().put("v", 1).put("files", files)) as SectionRead.Ok
        assertEquals(mapOf("browser_prefs" to mapOf("https_only" to "PRIVATE"), "doh_prefs" to mapOf("provider" to "MULLVAD")), read.value)
        // 5 bad browser keys, the cookies file (1 key), the custom address with a password in it.
        assertEquals(7, read.skipped)
    }

    @Test(timeout = 10_000)
    fun settingsValuesRoundTripThroughTheirChecks() {
        val values: SettingsValues =
            mapOf(
                "browser_prefs" to
                    mapOf(
                        "homepage" to NoValue,
                        "never_save_logins" to setOf("https://a.example", "http://b.example:8080"),
                        "pinned_sites" to listOf(PinnedSite("https://p.example/", "P")),
                        "app_theme" to "DARK",
                    ),
                "search_prefs" to mapOf("custom_engines" to listOf(BackupSearchEngine("Mine", "https://s.example/?q=%s")), "engine" to "Mine"),
                "toolbar_prefs" to mapOf("toolbar_button" to "FIND", "menu_hidden" to setOf("FIND")),
                "clear_exit_prefs" to mapOf("exceptions" to setOf("example.com")),
            )
        val read = roundTrip(SettingsSection.encode(values), SettingsSection::decode)
        assertEquals(values, read.value)
    }

    @Test(timeout = 10_000)
    fun menuOrderDropsUnknownNamesAndNeverHoldsSettings() {
        val order = JSONArray(listOf("FIND", "NO_SUCH_ACTION", "SETTINGS", "FIND"))
        val files = JSONObject().put("toolbar_prefs", JSONObject().put("menu_order", order).put("menu_hidden", JSONArray(listOf("SETTINGS", "FIND"))))
        val read = SettingsSection.decode(JSONObject().put("v", 1).put("files", files)) as SectionRead.Ok
        @Suppress("UNCHECKED_CAST")
        val menu = read.value.getValue("toolbar_prefs")["menu_order"] as List<String>
        assertEquals(CUSTOMIZABLE_MENU_ACTIONS.map { it.name }.toSet(), menu.toSet())
        assertEquals(menu.size, menu.toSet().size)
        assertFalse(MenuAction.SETTINGS.name in menu)
        assertEquals(setOf("FIND"), read.value.getValue("toolbar_prefs")["menu_hidden"])
    }

    @Test(timeout = 10_000)
    fun sitePermissionsUseTheStoreRules() {
        val ok = SitePermissionDecision(SitePermissionKey(SitePermissionKind.CAMERA, "https://cam.example"), true)
        val value = SitePermissionsBackup(listOf(ok), mapOf("js.example" to false), mapOf("d.example" to DesktopChoice.DECLINED))
        val encoded = SitePermissionsSection.encode(value)
        assertEquals(value, roundTrip(encoded, SitePermissionsSection::decode).value)

        encoded.getJSONArray("decisions").put(JSONObject().put("kind", "CAMERA").put("origin", "https://CAM.example/path").put("allow", true))
        encoded.getJSONArray("javascript").put(JSONObject().put("host", "bad host").put("on", true))
        encoded.getJSONArray("desktop").put(JSONObject().put("host", "e.example").put("choice", "SIDEWAYS"))
        val read = SitePermissionsSection.decode(encoded) as SectionRead.Ok
        assertEquals(value, read.value)
        assertEquals(3, read.skipped)
    }

    @Test(timeout = 10_000)
    fun tabsCarryNoStateAndKeepTheirGroups() {
        val value =
            TabsBackup(
                listOf(
                    PersistedTab(7, "https://a.example/", "A", desktop = true, savedState = "BLOB", groupId = 3),
                    PersistedTab(8, "https://b.example/", "B", desktop = false, savedState = null),
                ),
                listOf(TabGroup(3, "Trip", TabGroupColor.BLUE, collapsed = true)),
            )
        val encoded = TabsSection.encode(value)
        assertFalse(encoded.toString().contains("BLOB"))
        val tabsJson = encoded.getJSONArray("tabs")
        tabsJson.put(JSONObject().put("url", "about:config").put("title", "x"))
        tabsJson.put(JSONObject().put("url", "https://c.example/").put("group", 99))
        encoded.getJSONArray("groups").put(JSONObject().put("id", 4).put("name", "Odd").put("color", "ULTRAVIOLET"))
        val read = TabsSection.decode(JSONObject(encoded.toString())) as SectionRead.Ok
        assertEquals(listOf("https://a.example/", "https://b.example/", "https://c.example/"), read.value.tabs.map { it.url })
        assertEquals(listOf(3L, null, null), read.value.tabs.map { it.groupId })
        assertEquals(listOf(true, false, false), read.value.tabs.map { it.desktop })
        assertTrue(read.value.tabs.all { it.savedState == null })
        assertEquals(TabGroupColor.GREY, read.value.groups.first { it.id == 4L }.color)
        assertEquals(1, read.skipped)
    }

    @Test(timeout = 10_000)
    fun addonsListOnlyCuratedIds() {
        val encoded = AddonsSection.encode(AddonsBackup(listOf(BackupAddon("addon@darkreader.org", false)), ublockOrigin = true))
        encoded.getJSONArray("items").put(JSONObject().put("id", "evil@example.com").put("enabled", true))
        val read = AddonsSection.decode(JSONObject(encoded.toString())) as SectionRead.Ok
        assertEquals(AddonsBackup(listOf(BackupAddon("addon@darkreader.org", false)), ublockOrigin = true), read.value)
        assertEquals(1, read.skipped)
    }

    @Test(timeout = 10_000)
    fun aNewerOrShapelessSectionIsNotRead() {
        assertEquals(SectionRead.Newer(2), BookmarksSection.decode(JSONObject().put("v", 2).put("items", JSONArray())))
        assertEquals(SectionRead.Damaged, BookmarksSection.decode(JSONObject().put("items", JSONArray())))
        assertEquals(SectionRead.Damaged, BookmarksSection.decode(JSONObject().put("v", 1).put("items", "nope")))
    }
}

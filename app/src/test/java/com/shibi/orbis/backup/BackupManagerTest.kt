package com.shibi.orbis.backup

import com.shibi.orbis.addons.AddonState
import com.shibi.orbis.engine.SitePermissionKey
import com.shibi.orbis.engine.SitePermissionKind
import com.shibi.orbis.history.HistoryEntry
import com.shibi.orbis.settings.CUSTOMIZABLE_MENU_ACTIONS
import com.shibi.orbis.settings.DarkMode
import com.shibi.orbis.settings.DohProvider
import com.shibi.orbis.settings.HttpsOnlyMode
import com.shibi.orbis.settings.ReaderTheme
import com.shibi.orbis.settings.ToolbarButton
import com.shibi.orbis.tabs.PersistedTab
import com.shibi.orbis.tabs.TabGroup
import com.shibi.orbis.tabs.TabGroupColor
import com.shibi.orbis.tabs.TabsSnapshot
import com.shibi.orbis.ui.browser.MenuAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupManagerTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val crypto = BackupCrypto(dispatcher, 100_000, BackupCrypto.secureRandomBytes())

    @After
    fun tearDown() = scope.cancel()

    private fun pw() = "correct horse battery".toCharArray()

    private fun world(prepare: BackupWorld.() -> Unit = {}): BackupWorld =
        BackupWorld(scope, dispatcher, scheduler).apply {
            prepare()
            load()
        }

    private val darkReader = "addon@darkreader.org"
    private val camera = SitePermissionKey(SitePermissionKind.CAMERA, "https://cam.example")
    private val location = SitePermissionKey(SitePermissionKind.LOCATION, "https://map.example", embedder = "https://top.example")

    /** A world with something in every section, a private tab and data a backup must never hold. */
    private fun filled(): BackupWorld {
        val w =
            world {
                tabStore.snapshot =
                    TabsSnapshot(
                        listOf(
                            PersistedTab(1, "https://a.example/", "A", desktop = true, savedState = "STATE-FROM-DISK", groupId = 1),
                            PersistedTab(2, "https://c.example/", "C", desktop = false, savedState = null, groupId = 1),
                            PersistedTab(3, "https://b.example/", "B", desktop = false, savedState = null),
                        ),
                        selectedId = 1,
                        groups = listOf(TabGroup(1, "Trip", TabGroupColor.BLUE)),
                    )
                historyStore.data = listOf(HistoryEntry("https://seen.example/", "Seen", 4_000L, 3), HistoryEntry("https://old.example/", "Old", 1_000L, 1))
                browserPrefs.torrentIpNoteSeen = true
                browserPrefs.hiddenSites = setOf("hidden.example")
                addonStates = mapOf(darkReader to AddonState.Installed("4.9", enabled = false, allowedInPrivate = false, optionsPageUrl = null))
                ublockInstalled = true
            }
        w.bookmarks.add("https://a.example/", "A")
        w.bookmarks.add("https://b.example/", "B")
        w.bookmarks.moveToFolder(w.bookmarks.entries.value.first { it.url == "https://a.example/" }.id, "Work")
        w.logins.save("https://site.example", "me", "pw,1\"x")
        w.logins.save("https://other.example", "you", "pw2", httpRealm = "realm")
        w.logins.setNeverSave("https://never.example", true)
        with(w.browserSettings) {
            setHttpsOnly(HttpsOnlyMode.PRIVATE)
            setBlockContentTrackers(true)
            setDarkMode(DarkMode.DARK)
            setThemeId("graphite")
            setTextZoomPercent(150)
            setHomepage("https://home.example/")
            setJavaScript(false)
            setProtectedVideo(false)
            setReaderTheme(ReaderTheme.SEPIA)
            pinSite("https://pin.example/", "Pin")
            rememberJavaScript("https://js.example/x", true, isPrivate = false)
            rememberDesktop("https://d.example/", true, isPrivate = false)
            declineAutoDesktop("https://dec.example/", isPrivate = false)
            rememberAutoDesktop("https://auto.example/", isPrivate = false)
            // A private choice lives in memory only and is never backed up.
            rememberJavaScript("https://privatechoice.example/", true, isPrivate = true)
        }
        val mine = (w.searchEngine.addCustom("Mine", "https://s.example/?q=%s") as com.shibi.orbis.engine.AddResult.Added).engine
        w.searchEngine.select(mine)
        w.toolbar.setToolbarButton(ToolbarButton.FIND)
        w.toolbar.setShown(MenuAction.FIND, false)
        w.toolbar.moveDown(CUSTOMIZABLE_MENU_ACTIONS.first())
        w.doh.setEnabled(true)
        w.doh.setProvider(DohProvider.CUSTOM)
        w.doh.setCustomUrl("https://dns.example/q")
        w.clearPrefs.enabled = true
        w.clearPrefs.exceptions = setOf("example.com")
        w.privacy.lockPrivateTabs = true
        w.torrentSettings.update { it.copy(wifiOnly = false, stopSeeding = false, seedRatioLimit = 2.5f, dht = false, pex = false, lsd = true, downBps = 500_000, upBps = 120_000) }
        w.sitePermissions.record(camera, allow = true, remember = true)
        w.sitePermissions.record(location, allow = false, remember = true)
        w.sitePermissions.record(SitePermissionKey(SitePermissionKind.MICROPHONE, "https://once.example"), allow = true, remember = false)
        w.tabs.newPrivateTab("https://private.example/secret")
        scheduler.advanceUntilIdle()
        return w
    }

    private fun settingsOf(w: BackupWorld): Map<String, Map<String, Any?>> =
        w.manager.settings().mapValues { (_, keys) -> keys.mapValues { it.value.read() } }

    private fun normalTabs(w: BackupWorld) = w.tabs.tabs.value.filter { !it.isPrivate }

    @Test(timeout = 60_000)
    fun createThenRestoreIntoAnEmptyAppGivesTheSameData() =
        runTest(dispatcher) {
            val from = filled()
            val file = from.manager.create(pw())
            val to = world()
            val result = to.manager.restore(file, pw()) as RestoreResult.Restored
            scheduler.advanceUntilIdle()

            assertEquals(emptySet<String>(), result.failedSections)
            assertEquals(emptyMap<String, SectionSkip>(), result.skippedSections)
            assertEquals(
                from.bookmarks.entries.value.map { Triple(it.url, it.title, it.folder) }.toSet(),
                to.bookmarks.entries.value.map { Triple(it.url, it.title, it.folder) }.toSet(),
            )
            assertEquals("Work", to.bookmarks.entries.value.first { it.url == "https://a.example/" }.folder)
            assertEquals(
                from.logins.logins.value.map { listOf(it.origin, it.username, it.password, it.httpRealm) }.toSet(),
                to.logins.logins.value.map { listOf(it.origin, it.username, it.password, it.httpRealm) }.toSet(),
            )
            // Every allow-listed setting, read back through the settings classes (so their flows show the new values).
            assertEquals(settingsOf(from), settingsOf(to))
            assertEquals(HttpsOnlyMode.PRIVATE, to.browserSettings.httpsOnly.value)
            // Protected video was turned off: the restored app has it off too (the default is on).
            assertFalse(to.browserSettings.protectedVideo.value)
            assertFalse(to.browserPrefs.protectedVideo)
            // "Also block content trackers" was on: the restored app has it on too (the default is off).
            assertTrue(to.browserSettings.blockContentTrackers.value)
            assertTrue(to.browserPrefs.blockContentTrackers)
            assertEquals("Mine", (to.searchEngine.selected.value as com.shibi.orbis.engine.SearchProvider.Custom).name)
            assertEquals("https://dns.example/q", to.doh.config.value.resolverUrl)
            assertEquals(MenuAction.SETTINGS, to.toolbar.menuOrder.value.last())
            assertFalse(MenuAction.SETTINGS in to.toolbar.menuHidden.value)
            // Not on the allow-list: not carried over.
            assertFalse(to.browserPrefs.torrentIpNoteSeen)
            assertEquals(emptySet<String>(), to.browserPrefs.hiddenSites)

            assertEquals(
                setOf(camera to true, location to false),
                to.sitePermissions.entries.value.map { it.key to it.allow }.toSet(),
            )
            assertTrue(to.browserSettings.javaScriptFor("https://js.example/", false))
            assertFalse(to.browserSettings.javaScriptFor("https://privatechoice.example/", false))
            assertTrue(to.browserSettings.desktopFor("https://d.example/", false))
            assertTrue(to.browserSettings.isAutoDesktopDeclined("https://dec.example/", false))
            assertTrue(to.browserSettings.isAutoDesktop("https://auto.example/", false))

            // The new app's blank tab, then the three restored ones with their group; nothing was loaded.
            val tabs = normalTabs(to)
            assertEquals(listOf("", "https://a.example/", "https://c.example/", "https://b.example/"), tabs.map { it.url })
            val group = to.tabs.groups.value.single()
            assertEquals("Trip" to TabGroupColor.BLUE, group.name to group.color)
            assertEquals(listOf(null, group.id, group.id, null), tabs.map { it.groupId })
            assertTrue(to.sessions.all { it.loads.isEmpty() })
            assertTrue(to.tabs.tabs.value.none { it.isPrivate })

            assertEquals(emptyList<HistoryEntry>(), to.history.entries.value)
            assertEquals(listOf(BackupAddon(darkReader, enabled = false)), result.addonsToOffer)
            assertTrue(result.offerUblockOrigin)
            assertEquals(SectionCounts(added = 2), result.counts[BackupSection.BOOKMARKS])
            assertEquals(SectionCounts(added = 2), result.counts[BackupSection.PASSWORDS])
            assertEquals(SectionCounts(added = 3), result.counts[BackupSection.TABS])
        }

    @Test(timeout = 60_000)
    fun historyIsRestoredWithItsOwnTimesWhenAskedFor() =
        runTest(dispatcher) {
            val from = filled()
            val file = from.manager.create(pw(), BackupOptions(includeHistory = true))
            val to = world()
            val result = to.manager.restore(file, pw()) as RestoreResult.Restored
            assertEquals(from.history.entries.value, to.history.entries.value)
            assertEquals(SectionCounts(added = 2), result.counts[BackupSection.HISTORY])
        }

    @Test(timeout = 60_000)
    fun restoringIntoAnAppThatHasTheSameDataAddsNothing() =
        runTest(dispatcher) {
            val w = filled()
            val file = w.manager.create(pw(), BackupOptions(includeHistory = true))
            val before = settingsOf(w)
            val tabsBefore = w.tabs.tabs.value
            val result = w.manager.restore(file, pw()) as RestoreResult.Restored
            scheduler.advanceUntilIdle()

            assertEquals(BackupSection.ALL.toSet(), result.counts.keys)
            for ((name, c) in result.counts) {
                assertEquals(name, 0, c.added)
                assertEquals(name, 0, c.replaced)
                assertTrue(name, c.skipped > 0)
            }
            assertEquals(2, w.bookmarks.entries.value.size)
            assertEquals(2, w.logins.logins.value.size)
            assertEquals(before, settingsOf(w))
            assertEquals(tabsBefore, w.tabs.tabs.value)
            assertEquals(2, w.history.entries.value.size)
            assertEquals(emptyList<BackupAddon>(), result.addonsToOffer)
            assertFalse(result.offerUblockOrigin)
        }

    @Test(timeout = 60_000)
    fun aKeyOffTheAllowListAndAValueThatFailsParsingAreNotWritten() =
        runTest(dispatcher) {
            val w = world()
            val browser =
                JSONObject()
                    .put("https_only", "OFF")
                    .put("torrent_ip_note_seen", true)
                    .put("hidden_sites", JSONArray(listOf("x.example")))
                    .put("text_zoom_percent", 5_000)
                    .put("force_dark", "yes")
            val files = JSONObject().put("browser_prefs", browser).put("ubo_prefs", JSONObject().put("decision", "DECLINED"))
            val result = w.manager.restore(seal(mapOf(BackupSection.SETTINGS to JSONObject().put("v", 1).put("files", files))), pw()) as RestoreResult.Restored

            assertEquals(HttpsOnlyMode.OFF, w.browserSettings.httpsOnly.value)
            assertFalse(w.browserPrefs.torrentIpNoteSeen)
            assertEquals(emptySet<String>(), w.browserPrefs.hiddenSites)
            assertEquals(100, w.browserSettings.textZoomPercent.value)
            assertFalse(w.browserSettings.forceDark.value)
            assertEquals(SectionCounts(replaced = 1, skipped = 5), result.counts[BackupSection.SETTINGS])
        }

    @Test(timeout = 60_000)
    fun thePayloadHoldsNoCookiePrivateTabDownloadSessionStateOrHistory() =
        runTest(dispatcher) {
            val w = filled()
            val file = w.manager.create(pw())
            val opened = crypto.open(file, pw()) as BackupOpenResult.Opened
            val text = String(opened.payload, Charsets.UTF_8)
            val root = JSONObject(text)
            val sections = root.getJSONObject("sections")
            assertEquals(
                setOf(BackupSection.SETTINGS, BackupSection.SITE_PERMISSIONS, BackupSection.BOOKMARKS, BackupSection.PASSWORDS, BackupSection.TABS, BackupSection.ADDONS),
                sections.keys().asSequence().toSet(),
            )
            val keys = HashSet<String>().also { collectKeys(sections, it) }
            val forbidden =
                setOf(
                    "cookie", "cookies", "siteData", "cache", "state", "savedState", "session", "private", "isPrivate",
                    "download", "downloads", "torrent", "torrents", "history", "visits", "visited", "stats", "thumbnail",
                    "decision", "last_update_check_millis", "update_blocked", "hidden_sites", "torrent_ip_note_seen",
                )
            assertEquals(emptySet<String>(), keys intersect forbidden)
            for (secret in listOf("private.example", "privatechoice.example", "once.example", "STATE-FROM-DISK", "SESSION-STATE-BLOB", "seen.example")) {
                assertFalse(secret, text.contains(secret))
            }
            val files = sections.getJSONObject(BackupSection.SETTINGS).getJSONObject("files")
            for (file2 in files.keys()) {
                val allowed = SettingsAllowList.files.getValue(file2).keys
                assertTrue(files.getJSONObject(file2).keys().asSequence().all { it in allowed })
            }
        }

    @Test(timeout = 60_000)
    fun anExtraFutureSectionIsSkippedAndTheRestRestores() =
        runTest(dispatcher) {
            val w = world()
            val file =
                seal(
                    mapOf(
                        BackupSection.BOOKMARKS to BookmarksSection.encode(listOf(BackupBookmark("https://x.example/", "X", 1L))),
                        "future" to JSONObject().put("v", 1).put("anything", JSONArray(listOf(1, 2))),
                    ),
                )
            val result = w.manager.restore(file, pw()) as RestoreResult.Restored
            assertEquals(mapOf("future" to SectionSkip.UNKNOWN), result.skippedSections)
            assertEquals(listOf("https://x.example/"), w.bookmarks.entries.value.map { it.url })
            assertEquals(SectionCounts(added = 1), result.counts[BackupSection.BOOKMARKS])
        }

    @Test(timeout = 60_000)
    fun aSectionWithAHigherVersionIsSkippedAndReported() =
        runTest(dispatcher) {
            val w = world()
            val newer = BookmarksSection.encode(listOf(BackupBookmark("https://x.example/", "X", 1L))).put("v", 2)
            val result = w.manager.restore(seal(mapOf(BackupSection.BOOKMARKS to newer)), pw()) as RestoreResult.Restored
            assertEquals(mapOf(BackupSection.BOOKMARKS to SectionSkip.NEWER), result.skippedSections)
            assertEquals(emptyList<String>(), w.bookmarks.entries.value.map { it.url })
        }

    @Test(timeout = 60_000)
    fun aSectionThatFailsToApplyDoesNotStopTheOthers() =
        runTest(dispatcher) {
            val file = filled().manager.create(pw())
            val to = world { loginStore.failLoad = true }
            val result = to.manager.restore(file, pw()) as RestoreResult.Restored
            assertEquals(setOf(BackupSection.PASSWORDS), result.failedSections)
            assertEquals(2, to.bookmarks.entries.value.size)
            assertEquals(HttpsOnlyMode.PRIVATE, to.browserSettings.httpsOnly.value)
        }

    @Test(timeout = 60_000)
    fun aWrongPasswordChangesNothing() =
        runTest(dispatcher) {
            val file = filled().manager.create(pw())
            val to = world()
            assertEquals(RestoreResult.Failed(BackupError.WRONG_PASSWORD_OR_DAMAGED), to.manager.restore(file, "wrong password!".toCharArray()))
            assertEquals(0, to.bookmarks.entries.value.size)
        }

    @Test(timeout = 60_000)
    fun createRefusesAShortPasswordAndUnloadedPasswords() =
        runTest(dispatcher) {
            try {
                filled().manager.create("short".toCharArray())
                fail("short password accepted")
            } catch (e: BackupRefusedException) {
                assertEquals(BackupSealError.PASSWORD_TOO_SHORT, e.reason)
            }
            try {
                world { loginStore.failLoad = true }.manager.create(pw())
                fail("backup made without the saved passwords")
            } catch (e: BackupNotReadyException) {
                assertEquals(BackupSection.PASSWORDS, e.section)
            }
        }

    /** Passwords and tabs ready, so only the stores left out of [bookmarks] and [history] are in question. */
    private fun partlyLoaded(bookmarks: Boolean = true, history: Boolean = true): BackupWorld =
        BackupWorld(scope, dispatcher, scheduler).apply {
            bookmarkStore.data = listOf(com.shibi.orbis.bookmarks.Bookmark(1, "https://kept.example/", "Kept", 1L))
            logins.load()
            tabs.restore()
            if (bookmarks) this.bookmarks.load()
            if (history) this.history.load()
            scheduler.advanceUntilIdle()
        }

    @Test(timeout = 60_000)
    fun createWaitsForBookmarksThenHoldsThem() =
        runTest(dispatcher) {
            val w = partlyLoaded(bookmarks = false)
            assertFalse(w.bookmarks.loaded)
            try {
                w.manager.create(pw())
                fail("backup made before the bookmarks loaded")
            } catch (e: BackupNotReadyException) {
                assertEquals(BackupSection.BOOKMARKS, e.section)
            }
            w.bookmarks.load()
            scheduler.advanceUntilIdle()
            assertTrue(w.bookmarks.loaded)
            val opened = crypto.open(w.manager.create(pw()), pw()) as BackupOpenResult.Opened
            assertTrue(String(opened.payload, Charsets.UTF_8).contains("https://kept.example/"))
        }

    @Test(timeout = 60_000)
    fun historyIsWaitedForOnlyWhenAskedFor() =
        runTest(dispatcher) {
            val w = partlyLoaded(history = false)
            assertFalse(w.history.loaded)
            w.manager.create(pw())
            try {
                w.manager.create(pw(), BackupOptions(includeHistory = true))
                fail("history backup made before history loaded")
            } catch (e: BackupNotReadyException) {
                assertEquals(BackupSection.HISTORY, e.section)
            }
            w.history.load()
            scheduler.advanceUntilIdle()
            w.manager.create(pw(), BackupOptions(includeHistory = true))
        }

    @Test(timeout = 60_000)
    fun aRestoreBeforeBookmarksHaveLoadedChangesNothingAndSaysSo() =
        runTest(dispatcher) {
            val file = filled().manager.create(pw())
            val to = partlyLoaded(bookmarks = false)
            try {
                to.manager.restore(file, pw())
                fail("restored before the bookmarks loaded")
            } catch (e: BackupNotReadyException) {
                assertEquals(BackupSection.BOOKMARKS, e.section)
            }
            assertEquals(HttpsOnlyMode.ALL, to.browserSettings.httpsOnly.value)
            assertEquals(emptyList<Any>(), to.logins.logins.value)
            // Once loaded, the stored bookmark is still there: nothing was lost to the late load.
            to.bookmarks.load()
            scheduler.advanceUntilIdle()
            assertEquals(listOf("https://kept.example/"), to.bookmarks.entries.value.map { it.url })
        }

    @Test(timeout = 60_000)
    fun everyTorrentSettingIsRestoredAndAnOutOfRangeValueIsSkipped() =
        runTest(dispatcher) {
            val from = filled()
            val to = world()
            val result = to.manager.restore(from.manager.create(pw()), pw()) as RestoreResult.Restored
            assertEquals(from.torrentSettings.settings.value, to.torrentSettings.settings.value)
            assertEquals(2.5f, to.torrentSettings.settings.value.seedRatioLimit)
            assertEquals(emptySet<String>(), result.failedSections)

            val bad = world()
            val torrent =
                JSONObject()
                    .put("wifi_only", true)
                    .put("down_bps", -5)
                    .put("up_bps", 2_000_000_000)
                    .put("seed_ratio", 500)
                    .put("listen_port", 50_000)
                    .put("schema", 1)
            val files = JSONObject().put("torrent_settings", torrent)
            val r = bad.manager.restore(seal(mapOf(BackupSection.SETTINGS to JSONObject().put("v", 1).put("files", files))), pw()) as RestoreResult.Restored
            val t = bad.torrentSettings.settings.value
            assertEquals(true, t.wifiOnly)
            assertEquals(0, t.downBps)
            assertEquals(0, t.upBps)
            assertEquals(null, t.seedRatioLimit)
            assertFalse(bad.torrentPrefs.map.containsKey("listen_port"))
            assertEquals(SectionCounts(replaced = 1, skipped = 5), r.counts[BackupSection.SETTINGS])
        }

    @Test(timeout = 10_000)
    fun everyAllowListedKeyHasAReaderAndAWriter() {
        val w = world()
        assertEquals(
            SettingsAllowList.files.mapValues { it.value.keys },
            w.manager.settings().mapValues { it.value.keys },
        )
    }

    private suspend fun seal(sections: Map<String, JSONObject>): ByteArray =
        (crypto.seal(BackupPayload(sections).encode(), pw(), "test") as BackupSealResult.Sealed).file

    private fun collectKeys(value: Any?, into: MutableSet<String>) {
        when (value) {
            is JSONObject -> for (k in value.keys()) {
                into += k
                collectKeys(value.get(k), into)
            }
            is JSONArray -> for (i in 0 until value.length()) collectKeys(value.get(i), into)
        }
    }
}

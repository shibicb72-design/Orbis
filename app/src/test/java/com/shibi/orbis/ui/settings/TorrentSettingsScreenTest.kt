package com.shibi.orbis.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.shibi.orbis.engine.SearchEngine
import com.shibi.orbis.settings.BrowserSettings
import com.shibi.orbis.settings.MagnetHandler
import com.shibi.orbis.settings.SharedPreferencesBrowserPrefs
import com.shibi.orbis.torrent.SharedPreferencesTorrentPrefs
import com.shibi.orbis.torrent.TorrentSettings
import com.shibi.orbis.torrent.TorrentSettingsStore
import com.shibi.orbis.ubo.UboState
import com.shibi.orbis.ui.browser.ComposeActivityRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TorrentSettingsScreenTest {
    @get:Rule(order = 0)
    val registration = ComposeActivityRegistration()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val torrentPrefs get() = context.getSharedPreferences(SharedPreferencesTorrentPrefs.FILE_NAME, Context.MODE_PRIVATE)
    private val browserPrefs get() = context.getSharedPreferences(SharedPreferencesBrowserPrefs.FILE_NAME, Context.MODE_PRIVATE)
    private lateinit var store: TorrentSettingsStore
    private lateinit var browser: BrowserSettings

    @Before
    fun setUp() {
        torrentPrefs.edit().clear().commit()
        browserPrefs.edit().clear().commit()
        store = TorrentSettingsStore(torrentPrefs)
        browser = BrowserSettings(SharedPreferencesBrowserPrefs(browserPrefs))
    }

    private fun show() {
        rule.setContent { MaterialTheme { TorrentSettingsScreen(store = store, browser = browser, onBack = {}) } }
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** What a fresh store reads from the file: the change was written, not only shown. */
    private fun stored(): TorrentSettings = TorrentSettingsStore(torrentPrefs).settings.value

    @Test
    fun everySwitchTogglesTheStore() {
        show()
        assertEquals(TorrentSettings(), store.settings.value)
        tap("Download over Wi-Fi only")
        assertFalse(store.settings.value.wifiOnly)
        tap("Stop seeding when finished")
        assertFalse(store.settings.value.stopSeeding)
        tap("DHT")
        assertFalse(store.settings.value.dht)
        tap("Peer exchange")
        assertFalse(store.settings.value.pex)
        tap("Local peers")
        assertFalse(store.settings.value.lsd)
        assertEquals(TorrentSettings(wifiOnly = false, stopSeeding = false, dht = false, pex = false, lsd = false), stored())
        // And back on.
        tap("DHT")
        assertTrue(stored().dht)
    }

    @Test
    fun theSpeedLimitDialogRejectsTooMuchAndAccepts512() {
        show()
        assertEquals(2, rule.onAllNodesWithText("No limit").fetchSemanticsNodes().size)
        tap("Download speed limit")
        rule.onNode(hasSetTextAction()).performTextReplacement("1000001")
        rule.waitForIdle()
        rule.onNodeWithText("Enter a number from 0 to 1,000,000").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextReplacement("512")
        rule.waitForIdle()
        rule.onAllNodesWithText("Enter a number from 0 to 1,000,000").assertCountEquals(0)
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(512_000, store.settings.value.downBps)
        assertEquals(512_000, stored().downBps)
        assertEquals(0, stored().upBps)
        rule.onNodeWithText("512 KB/s").assertExists()

        // 0 is no limit again.
        tap("Upload speed limit")
        rule.onNode(hasSetTextAction()).performTextReplacement("64")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(64_000, stored().upBps)
        tap("Download speed limit")
        rule.onNode(hasSetTextAction()).performTextReplacement("0")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(0, stored().downBps)
    }

    @Test
    fun theRatioRowExistsOnlyWhileStopSeedingIsOff() {
        show()
        rule.onAllNodesWithText("Stop at ratio").assertCountEquals(0)
        tap("Stop seeding when finished")
        rule.onNodeWithText("Stop at ratio").assertExists()
        tap("Stop at ratio")
        rule.onNode(hasSetTextAction()).performTextReplacement("0")
        rule.waitForIdle()
        rule.onNodeWithText("Enter a ratio from 0.1 to 100").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextReplacement("0.1")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(0.1f, stored().seedRatioLimit)
        rule.onNodeWithText("0.1").assertExists()
        // Back on: the row goes, the limit stays stored for the next time.
        tap("Stop seeding when finished")
        rule.onAllNodesWithText("Stop at ratio").assertCountEquals(0)
        assertEquals(0.1f, stored().seedRatioLimit)
    }

    @Test
    fun theIpNoteOpensAndItsButtonShowsTheNoteAgain() {
        browser.markTorrentIpNoteSeen()
        assertTrue(browser.torrentIpNoteSeen.value)
        show()
        tap("How torrents share your IP address")
        rule.onNodeWithText("Torrent downloads connect directly to other people", substring = true).assertExists()
        rule.onNodeWithText("DHT, peer exchange and local peers", substring = true).assertExists()
        rule.onNodeWithText("Show the note before the next download").performClick()
        rule.waitForIdle()
        assertFalse(browser.torrentIpNoteSeen.value)
        assertFalse(BrowserSettings(SharedPreferencesBrowserPrefs(browserPrefs)).torrentIpNoteSeen.value)
    }

    @Test
    fun theMagnetLinksRowPersistsTheChoice() {
        assertEquals(MagnetHandler.ORBIS, browser.magnetHandler.value)
        assertTrue(browser.magnetsToOrbis())
        show()
        tap("Magnet links")
        rule.onNodeWithText("Open in another app").performClick()
        rule.waitForIdle()
        assertEquals(MagnetHandler.OTHER_APP, browser.magnetHandler.value)
        assertFalse(browser.magnetsToOrbis())
        // Written through: a fresh settings object reads the choice back.
        assertEquals(MagnetHandler.OTHER_APP, BrowserSettings(SharedPreferencesBrowserPrefs(browserPrefs)).magnetHandler.value)
        assertEquals("OTHER_APP", browserPrefs.getString("magnet_handler", null))
        // The row shows the choice; back to Orbis.
        tap("Open in another app")
        rule.onNodeWithText("Download with Orbis").performClick()
        rule.waitForIdle()
        assertEquals(MagnetHandler.ORBIS, BrowserSettings(SharedPreferencesBrowserPrefs(browserPrefs)).magnetHandler.value)
    }

    @Test
    fun settingsHasATorrentsRowAndNoMagnetLinksRow() {
        var opened = 0
        rule.setContent {
            MaterialTheme {
                SettingsScreen(
                    uboStateFlow = MutableStateFlow(UboState.NotInstalled),
                    searchEngineFlow = MutableStateFlow(SearchEngine.DEFAULT),
                    onSelectSearchEngine = {},
                    homepageFlow = MutableStateFlow(null),
                    onSetHomepage = {},
                    searchSuggestionsFlow = MutableStateFlow(true),
                    onSetSearchSuggestions = {},
                    textZoomFlow = MutableStateFlow(100),
                    onSetTextZoom = {},
                    forceZoomFlow = MutableStateFlow(false),
                    onSetForceZoom = {},
                    onBack = {},
                    onOpenErrorLog = {},
                    onOpenUbo = {},
                    onOpenSitePermissions = {},
                    onOpenClearData = {},
                    site = browser,
                    versionName = "0.5.0",
                    onOpenPlayListing = {},
                    onOpenTorrents = { opened++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Wi-Fi only, speed, seeding, magnet links").performScrollTo().assertExists()
        rule.onAllNodesWithText("Magnet links").assertCountEquals(0)
        tap("Torrents")
        assertEquals(1, opened)
    }
}

package com.shibi.orbis.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.update
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shibi.orbis.AppContainer
import com.shibi.orbis.clear.ClearState
import com.shibi.orbis.torrent.TorrentLog
import com.shibi.orbis.ui.bookmarks.BookmarksScreen
import com.shibi.orbis.ui.browser.BrowserScreen
import com.shibi.orbis.ui.downloads.DownloadsScreen
import com.shibi.orbis.ui.history.HistoryScreen
import com.shibi.orbis.ui.settings.ClearBrowsingDataScreen
import com.shibi.orbis.ui.settings.DebugLogScreen
import com.shibi.orbis.ui.settings.LicencesScreen
import com.shibi.orbis.ui.settings.SettingsScreen
import com.shibi.orbis.ui.settings.SitePermissionsScreen
import com.shibi.orbis.ui.settings.UboOnboardingScreen
import com.shibi.orbis.ui.settings.UboSettingsScreen
import com.shibi.orbis.ui.passwords.PasswordsScreen
import com.shibi.orbis.ui.theme.ROUTE_ENTER_MS
import com.shibi.orbis.ui.theme.ROUTE_EXIT_MS
import com.shibi.orbis.ui.theme.rememberMotionEnabled
import com.shibi.orbis.ui.theme.routeFades

private object Routes {
    const val Browser = "browser"
    const val Downloads = "downloads"
    const val Bookmarks = "bookmarks"
    const val History = "history"
    const val Settings = "settings"
    const val ErrorLog = "settings/error_log"
    const val Ubo = "settings/ubo"
    const val SitePermissions = "settings/site_permissions"
    const val ClearData = "settings/clear_data"
    const val Passwords = "settings/passwords"
    const val Licences = "settings/licences"
    const val QrScan = "qr_scan"
}

@Composable
fun OrbisRoot(container: AppContainer) {
    val nav = rememberNavController()
    val motionEnabled = rememberMotionEnabled()
    val showOnboarding by container.ubo.showOnboarding.collectAsStateWithLifecycle()
    if (showOnboarding) {
        // The browser (and its first network load) is not composed until the user has decided.
        UboOnboardingScreen(controller = container.ubo)
        return
    }
    // A link from another app: leave Settings/Downloads/etc. so the browser screen is shown and can take it.
    // Both of these wait while Clear browsing data is running: leaving that screen early could let a private tab be
    // opened while the engine is still clearing.
    val clearState by container.clear.state.collectAsStateWithLifecycle()
    val clearRunning = clearState is ClearState.Running
    val pendingExternalUrl by container.pendingExternalUrl.collectAsStateWithLifecycle()
    val pendingImported by container.pendingImports.head.collectAsStateWithLifecycle()
    LaunchedEffect(pendingExternalUrl, pendingImported, clearRunning) {
        if ((pendingExternalUrl != null || pendingImported != null) && !clearRunning) {
            nav.popBackStack(Routes.Browser, inclusive = false)
        }
    }
    // A download notification was tapped: open the Downloads screen once. Reached only after onboarding, so an
    // earlier tap simply waits.
    val pendingShowDownloads by container.pendingShowDownloads.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShowDownloads, clearRunning) {
        if (pendingShowDownloads && !clearRunning) {
            container.pendingShowDownloads.value = false
            nav.popBackStack(Routes.Browser, inclusive = false)
            nav.navigate(Routes.Downloads) { launchSingleTop = true }
        }
    }
    NavHost(
        navController = nav,
        startDestination = Routes.Browser,
        // Immediate across the browser boundary (the engine surface is never faded); the existing fades only between
        // secondary screens, and none at all with motion off. See routeFades.
        enterTransition = {
            if (routeFades(motionEnabled, initialState.destination.route, targetState.destination.route, Routes.Browser)) {
                fadeIn(tween(ROUTE_ENTER_MS))
            } else {
                EnterTransition.None
            }
        },
        exitTransition = {
            if (routeFades(motionEnabled, initialState.destination.route, targetState.destination.route, Routes.Browser)) {
                fadeOut(tween(ROUTE_EXIT_MS))
            } else {
                ExitTransition.None
            }
        },
        popEnterTransition = {
            if (routeFades(motionEnabled, initialState.destination.route, targetState.destination.route, Routes.Browser)) {
                fadeIn(tween(ROUTE_ENTER_MS))
            } else {
                EnterTransition.None
            }
        },
        popExitTransition = {
            if (routeFades(motionEnabled, initialState.destination.route, targetState.destination.route, Routes.Browser)) {
                fadeOut(tween(ROUTE_EXIT_MS))
            } else {
                ExitTransition.None
            }
        },
    ) {
        composable(Routes.Browser) {
            BrowserScreen(
                container = container,
                onOpenSettings = { nav.navigate(Routes.Settings) },
                onOpenDownloads = { nav.navigate(Routes.Downloads) },
                onOpenBookmarks = { nav.navigate(Routes.Bookmarks) },
                onOpenHistory = { nav.navigate(Routes.History) },
                onOpenQrScanner = { nav.navigate(Routes.QrScan) },
            )
        }
        composable(Routes.QrScan) {
            com.shibi.orbis.ui.qr.QrScanScreen(
                onOpenUrl = { url ->
                    container.tabs.loadInSelected(url)
                    nav.popBackStack()
                },
                onSearch = { text ->
                    com.shibi.orbis.engine.normalizeAddressInput(text, container.searchEngine.selected.value)?.let {
                        container.tabs.loadInSelected(it)
                    }
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.Bookmarks) {
            BookmarksScreen(
                manager = container.bookmarks,
                onOpen = { url ->
                    container.tabs.loadInSelected(url)
                    nav.popBackStack(Routes.Browser, inclusive = false)
                },
                onOpenInNewTab = { url ->
                    container.tabs.newTab(url)
                    nav.popBackStack(Routes.Browser, inclusive = false)
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.History) {
            HistoryScreen(
                manager = container.history,
                onOpen = { url ->
                    container.tabs.loadInSelected(url)
                    nav.popBackStack(Routes.Browser, inclusive = false)
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.Downloads) {
            DownloadsScreen(
                manager = container.downloads,
                torrents = container.torrents,
                onBack = { nav.popBackStack() },
                log = TorrentLog.sink(container.debugLog),
            )
        }
        composable(Routes.Passwords) {
            // Shows secrets: the window is protected (FLAG_SECURE, no Recents snapshot) while this screen is up.
            DisposableEffect(Unit) {
                container.sensitiveScreens.update { it + 1 }
                onDispose { container.sensitiveScreens.update { it - 1 } }
            }
            PasswordsScreen(
                manager = container.logins,
                offerSaveFlow = container.browserSettings.offerSavePasswords,
                onSetOfferSave = container.browserSettings::setOfferSavePasswords,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                uboStateFlow = container.ubo.state,
                searchEngineFlow = container.searchEngine.selected,
                onSelectSearchEngine = container.searchEngine::select,
                homepageFlow = container.browserSettings.homepage,
                onSetHomepage = { container.browserSettings.setHomepage(it) },
                searchSuggestionsFlow = container.browserSettings.searchSuggestions,
                onSetSearchSuggestions = container.browserSettings::setSearchSuggestions,
                textZoomFlow = container.browserSettings.textZoomPercent,
                onSetTextZoom = container.browserSettings::setTextZoomPercent,
                forceZoomFlow = container.browserSettings.forceZoom,
                onSetForceZoom = container.browserSettings::setForceZoom,
                onBack = { nav.popBackStack() },
                onOpenErrorLog = { nav.navigate(Routes.ErrorLog) },
                onOpenUbo = { nav.navigate(Routes.Ubo) },
                onOpenSitePermissions = { nav.navigate(Routes.SitePermissions) },
                onOpenClearData = { nav.navigate(Routes.ClearData) },
                onOpenPasswords = { nav.navigate(Routes.Passwords) },
                site = container.browserSettings,
                onOpenLicences = { nav.navigate(Routes.Licences) },
            )
        }
        composable(Routes.Licences) {
            LicencesScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ClearData) {
            ClearBrowsingDataScreen(
                tabs = container.tabs,
                bookmarks = container.bookmarks,
                controller = container.clear,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SitePermissions) {
            SitePermissionsScreen(
                decisions = container.sitePermissions,
                // The engine's own copy of a location answer is reset so a removed site is asked again.
                onRemoved = { container.engine.reconcileSitePermissions() },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.ErrorLog) {
            DebugLogScreen(
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.Ubo) {
            UboSettingsScreen(
                controller = container.ubo,
                onBack = { nav.popBackStack() },
                onOpenExtensionPage = { url ->
                    // A new normal tab; the browser screen then shows it.
                    container.tabs.newTab()?.let { container.tabs.sessionOf(it)?.loadExtensionPage(url) }
                    nav.popBackStack(Routes.Browser, inclusive = false)
                },
            )
        }
    }
}

package com.shibi.orbis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shibi.orbis.AppContainer
import com.shibi.orbis.clear.ClearState
import com.shibi.orbis.ui.bookmarks.BookmarksScreen
import com.shibi.orbis.ui.browser.BrowserScreen
import com.shibi.orbis.ui.downloads.DownloadsScreen
import com.shibi.orbis.ui.history.HistoryScreen
import com.shibi.orbis.ui.settings.ClearBrowsingDataScreen
import com.shibi.orbis.ui.settings.DebugLogScreen
import com.shibi.orbis.ui.settings.SettingsScreen
import com.shibi.orbis.ui.settings.SitePermissionsScreen
import com.shibi.orbis.ui.settings.UboOnboardingScreen
import com.shibi.orbis.ui.settings.UboSettingsScreen

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
}

@Composable
fun OrbisRoot(container: AppContainer) {
    val nav = rememberNavController()
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
    LaunchedEffect(pendingExternalUrl, clearRunning) {
        if (pendingExternalUrl != null && !clearRunning) nav.popBackStack(Routes.Browser, inclusive = false)
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
    NavHost(navController = nav, startDestination = Routes.Browser) {
        composable(Routes.Browser) {
            BrowserScreen(
                container = container,
                onOpenSettings = { nav.navigate(Routes.Settings) },
                onOpenDownloads = { nav.navigate(Routes.Downloads) },
                onOpenBookmarks = { nav.navigate(Routes.Bookmarks) },
                onOpenHistory = { nav.navigate(Routes.History) },
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
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                uboStateFlow = container.ubo.state,
                onBack = { nav.popBackStack() },
                onOpenErrorLog = { nav.navigate(Routes.ErrorLog) },
                onOpenUbo = { nav.navigate(Routes.Ubo) },
                onOpenSitePermissions = { nav.navigate(Routes.SitePermissions) },
                onOpenClearData = { nav.navigate(Routes.ClearData) },
            )
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
            )
        }
    }
}

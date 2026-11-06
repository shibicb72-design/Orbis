package com.shibi.orbis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shibi.orbis.AppContainer
import com.shibi.orbis.ui.browser.BrowserScreen
import com.shibi.orbis.ui.downloads.DownloadsScreen
import com.shibi.orbis.ui.settings.DebugLogScreen
import com.shibi.orbis.ui.settings.SettingsScreen
import com.shibi.orbis.ui.settings.UboOnboardingScreen
import com.shibi.orbis.ui.settings.UboSettingsScreen

private object Routes {
    const val Browser = "browser"
    const val Downloads = "downloads"
    const val Settings = "settings"
    const val ErrorLog = "settings/error_log"
    const val Ubo = "settings/ubo"
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
    val pendingExternalUrl by container.pendingExternalUrl.collectAsStateWithLifecycle()
    LaunchedEffect(pendingExternalUrl) {
        if (pendingExternalUrl != null) nav.popBackStack(Routes.Browser, inclusive = false)
    }
    NavHost(navController = nav, startDestination = Routes.Browser) {
        composable(Routes.Browser) {
            BrowserScreen(
                container = container,
                onOpenSettings = { nav.navigate(Routes.Settings) },
                onOpenDownloads = { nav.navigate(Routes.Downloads) },
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

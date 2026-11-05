package com.shibi.orbis.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shibi.orbis.AppContainer
import com.shibi.orbis.ui.browser.BrowserScreen
import com.shibi.orbis.ui.settings.DebugLogScreen
import com.shibi.orbis.ui.settings.SettingsScreen
import com.shibi.orbis.ui.settings.UboOnboardingStubScreen

private object Routes {
    const val Browser = "browser"
    const val Settings = "settings"
    const val ErrorLog = "settings/error_log"
    const val UboOnboarding = "settings/ubo_onboarding"
}

@Composable
fun OrbisRoot(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.Browser) {
        composable(Routes.Browser) {
            BrowserScreen(
                engine = container.engine,
                onOpenSettings = { nav.navigate(Routes.Settings) },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenErrorLog = { nav.navigate(Routes.ErrorLog) },
                onOpenUboOnboarding = { nav.navigate(Routes.UboOnboarding) },
            )
        }
        composable(Routes.ErrorLog) {
            DebugLogScreen(
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.UboOnboarding) {
            UboOnboardingStubScreen(onBack = { nav.popBackStack() })
        }
    }
}
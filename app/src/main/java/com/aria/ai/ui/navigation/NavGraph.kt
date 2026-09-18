package com.aria.ai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aria.ai.ui.screens.home.HomeScreen
import com.aria.ai.ui.screens.settings.ProvidersSettingsScreen
import com.aria.ai.ui.screens.settings.SettingsScreen

/** Every destination Aria ships with. */
object AriaRoutes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PROVIDERS = "providers"
}

/**
 * Aria's navigation graph: the conversation HUD is home, everything else is one
 * level away so the back gesture always returns to the assistant.
 */
@Composable
fun AriaNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = AriaRoutes.HOME) {

        composable(AriaRoutes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(AriaRoutes.SETTINGS) },
                onOpenProviders = { navController.navigate(AriaRoutes.PROVIDERS) }
            )
        }

        composable(AriaRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenProviders = { navController.navigate(AriaRoutes.PROVIDERS) }
            )
        }

        composable(AriaRoutes.PROVIDERS) {
            ProvidersSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
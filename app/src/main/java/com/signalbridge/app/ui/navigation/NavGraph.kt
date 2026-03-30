package com.signalbridge.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.signalbridge.app.auth.AuthRepository
import com.signalbridge.app.auth.TokenManager
import com.signalbridge.app.ui.screens.DashboardScreen
import com.signalbridge.app.ui.screens.LoginScreen
import com.signalbridge.app.ui.screens.SettingsScreen

/**
 * Navigation routes for the app.
 * Simple 3-screen structure: Login → Dashboard ↔ Settings
 */
object Routes {
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
}

@Composable
fun SignalBridgeNavGraph(
    navController: NavHostController,
    tokenManager: TokenManager,
    authRepository: AuthRepository,
    startDestination: String,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                tokenManager = tokenManager,
                authRepository = authRepository,
                onLoginSuccess = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                tokenManager = tokenManager,
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                tokenManager = tokenManager,
                authRepository = authRepository,
                onBack = {
                    navController.popBackStack()
                },
                onSignOut = {
                    tokenManager.clearAuth()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}

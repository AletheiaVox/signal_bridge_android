package com.signalbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.signalbridge.app.auth.AuthRepository
import com.signalbridge.app.ui.navigation.Routes
import com.signalbridge.app.ui.navigation.SignalBridgeNavGraph
import com.signalbridge.app.ui.theme.SignalBridgeTheme

class MainActivity : ComponentActivity() {

    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as SignalBridgeApp
        authRepository = AuthRepository(app.tokenManager)

        // If already logged in with a valid token, skip to dashboard
        val startDestination = if (app.tokenManager.isLoggedIn) {
            Routes.DASHBOARD
        } else {
            Routes.LOGIN
        }

        setContent {
            SignalBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    SignalBridgeNavGraph(
                        navController = navController,
                        tokenManager = app.tokenManager,
                        authRepository = authRepository,
                        startDestination = startDestination,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::authRepository.isInitialized) {
            authRepository.close()
        }
    }
}

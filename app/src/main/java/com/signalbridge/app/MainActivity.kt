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
import com.signalbridge.app.data.RelayState
import com.signalbridge.app.data.RelayStateHolder
import com.signalbridge.app.service.RelayService
import com.signalbridge.app.ui.navigation.Routes
import com.signalbridge.app.ui.navigation.SignalBridgeNavGraph
import com.signalbridge.app.ui.theme.SignalBridgeTheme
import com.signalbridge.app.util.SBLog

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

    override fun onResume() {
        super.onResume()
        // Re-arm the relay if it terminally gave up while we were backgrounded
        // (e.g. retries exhausted during a network outage). The service-side
        // handler (ACTION_RECHECK) existed but was never dispatched from
        // anywhere — this wires up that recovery path.
        RelayService.recheck(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::authRepository.isInitialized) {
            authRepository.close()
        }
    }
}

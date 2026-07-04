package com.signalbridge.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Monitors network transitions (WiFi ↔ mobile data).
 *
 * When Android switches networks, WebSocket connections silently die.
 * This monitor detects the transition so the relay can proactively
 * stop devices and reconnect rather than waiting for heartbeat timeout.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var onNetworkLost: (() -> Unit)? = null
    var onNetworkAvailable: (() -> Unit)? = null

    private var isRegistered = false
    private var currentNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            SBLog.i("NetworkMonitor", "Network available: $network")
            if (currentNetwork != null && currentNetwork != network) {
                // Network CHANGED (e.g., WiFi → mobile) — trigger reconnect
                SBLog.w("NetworkMonitor", "Network switched from $currentNetwork → $network")
                onNetworkLost?.invoke()
            }
            currentNetwork = network
            onNetworkAvailable?.invoke()
        }

        override fun onLost(network: Network) {
            SBLog.w("NetworkMonitor", "Network lost: $network")
            if (network == currentNetwork) {
                currentNetwork = null
                onNetworkLost?.invoke()
            }
        }
    }

    fun start() {
        if (isRegistered) return
        // Track only the DEFAULT network. registerNetworkCallback(INTERNET) fired
        // for EVERY matching network — on a phone with WiFi + mobile data both
        // enabled, the OS bringing the idle cellular link up/down produced
        // spurious available/lost callbacks (each stopping devices and churning
        // the relay) while the network actually in use never changed.
        connectivityManager.registerDefaultNetworkCallback(callback)
        isRegistered = true
        SBLog.i("NetworkMonitor", "Network monitoring started (default network)")
    }

    fun stop() {
        if (!isRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
        isRegistered = false
        currentNetwork = null
        SBLog.i("NetworkMonitor", "Network monitoring stopped")
    }
}

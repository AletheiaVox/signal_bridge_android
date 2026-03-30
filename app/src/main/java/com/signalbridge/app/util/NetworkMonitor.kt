package com.signalbridge.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

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
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        isRegistered = true
        SBLog.i("NetworkMonitor", "Network monitoring started")
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

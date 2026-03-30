package com.signalbridge.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.signalbridge.app.auth.TokenManager

/**
 * Application class — initializes singletons that outlive any single Activity.
 */
class SignalBridgeApp : Application() {

    lateinit var tokenManager: TokenManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        tokenManager = TokenManager(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(com.signalbridge.app.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,  // Low = no sound, but persistent
        ).apply {
            description = getString(com.signalbridge.app.R.string.notification_channel_description)
            setShowBadge(false)
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "signal_bridge_relay"
        const val NOTIFICATION_ID = 1

        lateinit var instance: SignalBridgeApp
            private set
    }
}

package com.signalbridge.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.signalbridge.app.MainActivity
import com.signalbridge.app.R
import com.signalbridge.app.SignalBridgeApp
import com.signalbridge.app.data.RelayState
import com.signalbridge.app.data.RelayStateHolder
import com.signalbridge.app.auth.TokenManager
import com.signalbridge.app.relay.RelayEngine
import com.signalbridge.app.util.NetworkMonitor
import com.signalbridge.app.util.SBLog
import kotlinx.coroutines.*

/**
 * Foreground service that runs the Signal Bridge relay.
 *
 * Maintains two WebSocket connections via [RelayEngine]:
 *   1. Upstream → VPS server (wss://server/ws/phone)
 *   2. Local → Intiface Central (ws://127.0.0.1:12345)
 */
class RelayService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var engine: RelayEngine? = null
    private var networkMonitor: NetworkMonitor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRelay()
            ACTION_STOP -> stopRelay()
            ACTION_EMERGENCY_STOP -> handleEmergencyStop()
            else -> startRelay()
        }
        return START_STICKY  // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Safety: best-effort stop all devices on service death.
        // Must use runBlocking here because serviceScope.cancel() follows immediately.
        // engine.emergencyStop() sends stop commands to Intiface + notifies server.
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(2000L) {  // 2s max — don't hang the main thread forever
                    engine?.emergencyStop()
                }
            }
        } catch (_: Exception) {
            // Best effort — if it times out or fails, we tried
            SBLog.w("RelayService", "onDestroy emergency stop timed out or failed")
        }

        networkMonitor?.stop()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Relay lifecycle ───────────────────────────────────────

    private fun startRelay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SignalBridgeApp.NOTIFICATION_ID,
                buildNotification("Connecting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(SignalBridgeApp.NOTIFICATION_ID, buildNotification("Connecting…"))
        }
        acquireWakeLock()

        // Get connection params from TokenManager
        val tokenManager = (application as SignalBridgeApp).tokenManager
        val token = tokenManager.token
        val serverUrl = tokenManager.serverUrl
        val intifaceUrl = tokenManager.intifaceUrl

        if (token == null) {
            SBLog.e("RelayService", "No token — can't start relay")
            RelayStateHolder.setError("Not signed in")
            stopRelay()
            return
        }

        // Create and start the engine
        val eng = RelayEngine(
            serverUrl = serverUrl,
            token = token,
            intifaceUrl = intifaceUrl,
            onNotificationUpdate = { status -> updateNotification(status) },
        )
        engine = eng

        // Monitor network transitions — stop devices + reconnect on WiFi↔mobile switch
        val monitor = NetworkMonitor(this)
        monitor.onNetworkLost = {
            SBLog.w("RelayService", "Network lost — stopping devices for safety")
            serviceScope.launch {
                eng.emergencyStop()
                // Engine's relay loop will detect the broken WebSocket and reconnect
            }
        }
        monitor.start()
        networkMonitor = monitor

        eng.start()
    }

    private fun stopRelay() {
        networkMonitor?.stop()
        networkMonitor = null

        serviceScope.launch {
            engine?.stop()
            engine = null
        }

        RelayStateHolder.reset()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleEmergencyStop() {
        SBLog.safety("EMERGENCY STOP triggered via service")

        // Stop all devices via engine
        serviceScope.launch {
            engine?.emergencyStop()
        }

        // Vibrate phone to confirm (distinctive pattern: long-short-long)
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(
                android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 300, 100, 100, 100, 300),  // long-short-long
                    -1  // don't repeat
                )
            )
        }

        updateNotification("STOPPED — all devices off")
    }

    // ── Notification ──────────────────────────────────────────

    private fun buildNotification(statusText: String): Notification {
        // Tap notification → open app
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // STOP ALL action button
        val stopIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, EmergencyStopReceiver::class.java).apply {
                action = ACTION_EMERGENCY_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, SignalBridgeApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Signal Bridge")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.notification_stop_all),
                stopIntent,
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(SignalBridgeApp.NOTIFICATION_ID, buildNotification(statusText))
    }

    // ── WakeLock ──────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SignalBridge::RelayWakeLock"
            ).apply {
                // 2-hour max timeout to prevent indefinite battery drain
                acquire(2 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ── Static helpers for starting/stopping from UI ──────────

    companion object {
        const val ACTION_START = "com.signalbridge.START"
        const val ACTION_STOP = "com.signalbridge.STOP"
        const val ACTION_EMERGENCY_STOP = "com.signalbridge.EMERGENCY_STOP"

        fun start(context: Context) {
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun emergencyStop(context: Context) {
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_EMERGENCY_STOP
            }
            context.startService(intent)
        }
    }
}

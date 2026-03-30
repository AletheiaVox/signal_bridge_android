package com.signalbridge.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service for volume key emergency stop interception.
 *
 * Detects two trigger patterns:
 *   1. Triple-press: 3 volume-down presses within 1.2 seconds
 *   2. Long-press: Continuous volume-down hold for 2 seconds
 *
 * Only active when the relay service is running.
 * Minimal capabilities: only key event filtering, no screen reading.
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    private val pressTimestamps = mutableListOf<Long>()
    private var isVolumeDownHeld = false

    // Handler-based long-press: schedule a delayed trigger on ACTION_DOWN,
    // cancel on ACTION_UP. This is reliable because onKeyEvent is NOT called
    // repeatedly during a hold — Android only sends ACTION_DOWN once (plus
    // unreliable repeats on some devices), then ACTION_UP on release.
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (isVolumeDownHeld && isRelayActive()) {
            triggerEmergencyStop()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            resetState()
            return false
        }

        // Only intercept when relay is active
        if (!isRelayActive()) {
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!isVolumeDownHeld) {
                    isVolumeDownHeld = true

                    // Schedule long-press trigger after 2 seconds
                    handler.postDelayed(longPressRunnable, 2000L)

                    // Record for triple-press detection
                    val now = System.currentTimeMillis()
                    pressTimestamps.add(now)

                    // Remove presses older than 1.2 seconds
                    pressTimestamps.removeAll { now - it > 1200L }

                    // Check for triple-press
                    if (pressTimestamps.size >= 3) {
                        val sorted = pressTimestamps.sorted()
                        val allDebounced = (1 until sorted.size).all {
                            sorted[it] - sorted[it - 1] >= 100L  // minimum gap to avoid bounce
                        }
                        if (allDebounced) {
                            triggerEmergencyStop()
                            return true  // consume the event
                        }
                    }
                }
            }

            KeyEvent.ACTION_UP -> {
                isVolumeDownHeld = false
                handler.removeCallbacks(longPressRunnable)
            }
        }

        // Don't consume the event — let volume still change
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only intercept key events
    }

    override fun onInterrupt() {
        resetState()
    }

    private fun triggerEmergencyStop() {
        resetState()
        RelayService.emergencyStop(this)
    }

    private fun resetState() {
        pressTimestamps.clear()
        isVolumeDownHeld = false
        handler.removeCallbacks(longPressRunnable)
    }

    private fun isRelayActive(): Boolean {
        // Check if volume key stop is enabled in settings
        val app = application as? com.signalbridge.app.SignalBridgeApp
        if (app != null && !app.tokenManager.volumeKeyStopEnabled) {
            return false  // User disabled volume key stop
        }

        val state = com.signalbridge.app.data.RelayStateHolder.state.value
        return state != com.signalbridge.app.data.RelayState.DISCONNECTED
    }
}

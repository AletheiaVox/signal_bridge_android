package com.signalbridge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver that triggers emergency stop from:
 *   1. The notification STOP ALL action button
 *   2. The VolumeKeyAccessibilityService
 *
 * All it does is forward the intent to RelayService.
 */
class EmergencyStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RelayService.emergencyStop(context)
    }
}

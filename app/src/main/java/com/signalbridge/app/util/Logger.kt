package com.signalbridge.app.util

import android.util.Log

/**
 * Simple logging wrapper with consistent tag prefix.
 * Supports both single-param (uses default tag) and two-param (uses subtag) forms.
 */
object SBLog {
    private const val TAG = "SignalBridge"

    fun d(message: String) = Log.d(TAG, message)
    fun d(subtag: String, message: String) = Log.d("$TAG.$subtag", message)

    fun i(message: String) = Log.i(TAG, message)
    fun i(subtag: String, message: String) = Log.i("$TAG.$subtag", message)

    fun w(message: String) = Log.w(TAG, message)
    fun w(subtag: String, message: String) = Log.w("$TAG.$subtag", message)

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
    fun e(subtag: String, message: String) = Log.e("$TAG.$subtag", message)

    fun safety(message: String) = Log.w("$TAG.SAFETY", message)
}

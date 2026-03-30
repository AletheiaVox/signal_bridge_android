package com.signalbridge.app.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Base64

/**
 * Secure token storage using Android Keystore-backed EncryptedSharedPreferences.
 *
 * Stores JWT token, username, user ID, and server URL.
 * Token is encrypted at rest — no plaintext in SharedPreferences.
 */
class TokenManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "signal_bridge_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, null) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var intifaceUrl: String
        get() = prefs.getString(KEY_INTIFACE_URL, null) ?: DEFAULT_INTIFACE_URL
        set(value) = prefs.edit().putString(KEY_INTIFACE_URL, value).apply()

    var volumeKeyStopEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_STOP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_STOP_ENABLED, value).apply()

    val isLoggedIn: Boolean
        get() = token != null && !isTokenExpired

    /**
     * Check if the JWT is expired by decoding the payload.
     * JWTs are base64(header).base64(payload).signature
     */
    val isTokenExpired: Boolean
        get() {
            val jwt = token ?: return true
            return try {
                val parts = jwt.split(".")
                if (parts.size != 3) return true
                val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING))
                val json = Json.parseToJsonElement(payload)
                val exp = json.jsonObject["exp"]?.jsonPrimitive?.long ?: return true
                System.currentTimeMillis() / 1000 > exp
            } catch (e: Exception) {
                true  // can't parse → treat as expired
            }
        }

    /**
     * Returns token expiry as a human-readable string, or null.
     */
    val tokenExpiryDisplay: String?
        get() {
            val jwt = token ?: return null
            return try {
                val parts = jwt.split(".")
                if (parts.size != 3) return null
                val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING))
                val json = Json.parseToJsonElement(payload)
                val exp = json.jsonObject["exp"]?.jsonPrimitive?.long ?: return null
                val remaining = exp - System.currentTimeMillis() / 1000
                when {
                    remaining <= 0 -> "Expired"
                    remaining < 3600 -> "${remaining / 60}m remaining"
                    remaining < 86400 -> "${remaining / 3600}h remaining"
                    else -> "${remaining / 86400}d remaining"
                }
            } catch (e: Exception) {
                null
            }
        }

    fun saveAuth(authToken: String, user: String, id: String) {
        token = authToken
        username = user
        userId = id
    }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_USER_ID)
            .apply()
    }

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_INTIFACE_URL = "intiface_url"
        private const val KEY_VOLUME_STOP_ENABLED = "volume_stop_enabled"

        // These match BuildConfig values but are fallbacks if BuildConfig isn't available
        private const val DEFAULT_SERVER_URL = "https://signal-bridge.duckdns.org"
        private const val DEFAULT_INTIFACE_URL = "ws://127.0.0.1:12345"
    }
}

// Helper extension for JSON parsing in the JWT decoder above
private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject
private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive
private val kotlinx.serialization.json.JsonPrimitive.long
    get() = this.content.toLongOrNull()

package com.signalbridge.app.auth

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP client for Signal Bridge VPS auth endpoints.
 *
 * Endpoints used:
 *   POST /auth/register  {"username", "password"} → {"user_id", "username", "token"}
 *   POST /auth/login     {"username", "password"} → {"user_id", "username", "token"}
 */
class AuthRepository(private val tokenManager: TokenManager) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // Without this, kotlinx.serialization OMITS any field whose value equals
        // its Kotlin default. SafetyConfig(governor_enabled = true) serialized to
        // a JSON body with no governor_enabled at all, so POST /safety/config
        // could never turn the governor back ON (false ≠ default, so turning it
        // OFF always worked — a one-way ratchet).
        encodeDefaults = true
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(this@AuthRepository.json)
        }
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    /**
     * Register a new account on the VPS.
     * Returns AuthResult.Success or AuthResult.Error.
     */
    suspend fun register(username: String, password: String): AuthResult {
        return doAuth("${tokenManager.serverUrl}/auth/register", username, password)
    }

    /**
     * Login to an existing account.
     * Returns AuthResult.Success or AuthResult.Error.
     */
    suspend fun login(username: String, password: String): AuthResult {
        return doAuth("${tokenManager.serverUrl}/auth/login", username, password)
    }

    /**
     * Check if the server is reachable.
     */
    suspend fun healthCheck(): Boolean {
        return try {
            val response = client.get("${tokenManager.serverUrl}/health")
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fetch the effective safety config for the current user.
     * Returns a map of config keys → values, or null on error.
     */
    suspend fun getSafetyConfig(): SafetyConfig? {
        val token = tokenManager.token ?: return null
        return try {
            val response = client.get("${tokenManager.serverUrl}/safety/config") {
                header("Authorization", "Bearer $token")
            }
            if (response.status == HttpStatusCode.OK) {
                json.decodeFromString<SafetyConfig>(response.bodyAsText())
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Update per-user safety config overrides.
     * Returns the new effective config, or null on error.
     */
    suspend fun setSafetyConfig(updates: SafetyConfig): SafetyConfig? {
        val token = tokenManager.token ?: return null
        return try {
            val response = client.post("${tokenManager.serverUrl}/safety/config") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(updates)
            }
            if (response.status == HttpStatusCode.OK) {
                json.decodeFromString<SafetyConfig>(response.bodyAsText())
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun doAuth(url: String, username: String, password: String): AuthResult {
        return try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(AuthRequest(username = username, password = password))
            }

            val body = response.bodyAsText()

            when (response.status) {
                HttpStatusCode.OK -> {
                    val authResponse = json.decodeFromString<AuthResponse>(body)
                    tokenManager.saveAuth(
                        authToken = authResponse.token,
                        user = authResponse.username,
                        id = authResponse.userId,
                    )
                    AuthResult.Success(
                        username = authResponse.username,
                        userId = authResponse.userId,
                    )
                }
                HttpStatusCode.BadRequest,
                HttpStatusCode.Unauthorized,
                HttpStatusCode.Forbidden -> {
                    val error = try {
                        json.decodeFromString<ErrorResponse>(body)
                    } catch (e: Exception) {
                        ErrorResponse("Authentication failed")
                    }
                    AuthResult.Error(error.error)
                }
                HttpStatusCode.TooManyRequests -> {
                    AuthResult.Error("Too many attempts. Please wait a moment.")
                }
                else -> {
                    AuthResult.Error("Server error (${response.status.value})")
                }
            }
        } catch (e: java.net.ConnectException) {
            AuthResult.Error("Can't reach server. Check your connection and server URL.")
        } catch (e: java.net.UnknownHostException) {
            AuthResult.Error("Server not found. Check the URL in Settings.")
        } catch (e: Exception) {
            AuthResult.Error("Connection error: ${e.message ?: "Unknown error"}")
        }
    }

    fun close() {
        client.close()
    }
}

// ── Request / Response models ──────────────────────────────────────────

@Serializable
data class AuthRequest(
    val username: String,
    val password: String,
)

@Serializable
data class AuthResponse(
    val user_id: String,
    val username: String,
    val token: String,
) {
    // Convenience properties with Kotlin naming
    val userId get() = user_id
}

@Serializable
data class ErrorResponse(
    val error: String,
)

@Serializable
data class SafetyConfig(
    val governor_enabled: Boolean = true,
    val heat_rate: Double = 3.0,
    val cool_rate: Double = 2.0,
    val cooldown_threshold: Double = 90.0,
    val cooldown_exit: Double = 30.0,
    val cooldown_duration: Double = 30.0,
)

// ── Result type ────────────────────────────────────────────────────────

sealed class AuthResult {
    data class Success(val username: String, val userId: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

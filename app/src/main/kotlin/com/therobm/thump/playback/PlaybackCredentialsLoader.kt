package com.therobm.thump.playback

import android.content.Context
import com.therobm.thump.subsonic.SubsonicAuthMode
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicCredentials
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds a SubsonicClient from the credentials the app persisted in SharedPreferences.
 *
 * The playback service runs in its own process scope and cannot reach the SubsonicClient the
 * app composable holds, so it reads the same backing store the Settings screen writes to.
 * SharedPreferences is the contract between the UI process and the service process here.
 *
 * Returns null when credentials are missing or blank — callers (browse callbacks) should treat
 * that as "nothing to show" and surface an empty list rather than crashing.
 */
class PlaybackCredentialsLoader(private val applicationContext: Context) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonDecoder: Json = Json {
        ignoreUnknownKeys = true
    }

    fun loadSubsonicClient(): SubsonicClient? {
        val sharedPreferences = applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val serverUrl = readStringOrBlank(sharedPreferences.getString(PREFS_KEY_SERVER_URL, null))
        val username = readStringOrBlank(sharedPreferences.getString(PREFS_KEY_USERNAME, null))
        val password = readStringOrBlank(sharedPreferences.getString(PREFS_KEY_PASSWORD, null))
        val useTokenAuth = sharedPreferences.getBoolean(PREFS_KEY_USE_TOKEN_AUTH, true)

        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            return null
        }
        val authMode: SubsonicAuthMode
        if (useTokenAuth) {
            authMode = SubsonicAuthMode.Token
        } else {
            authMode = SubsonicAuthMode.Legacy
        }
        return SubsonicClient(
            okHttpClient = httpClient,
            jsonDecoder = jsonDecoder,
            credentials = SubsonicCredentials(
                serverUrl = serverUrl.trim(),
                username = username.trim(),
                password = password,
            ),
            authMode = authMode,
        )
    }

    fun loadIsPulseDetected(): Boolean {
        val sharedPreferences = applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val cachedFor = sharedPreferences.getString(PREFS_KEY_PULSE_DETECTED_FOR_URL, null)
        val currentUrl = readStringOrBlank(sharedPreferences.getString(PREFS_KEY_SERVER_URL, null)).trim()
        if (cachedFor == null) {
            return false
        }
        if (cachedFor != currentUrl) {
            return false
        }
        return sharedPreferences.getBoolean(PREFS_KEY_PULSE_DETECTED_VALUE, false)
    }

    private fun readStringOrBlank(stored: String?): String {
        if (stored == null) {
            return ""
        }
        return stored
    }

    companion object {
        // Mirrored from MainActivity's prefs keys. Keep in sync if those change.
        private const val PREFS_NAME = "thump_settings"
        private const val PREFS_KEY_SERVER_URL = "server_url"
        private const val PREFS_KEY_USERNAME = "username"
        private const val PREFS_KEY_PASSWORD = "password"
        private const val PREFS_KEY_USE_TOKEN_AUTH = "use_token_auth"
        private const val PREFS_KEY_PULSE_DETECTED_FOR_URL = "pulse_detected_for_url"
        private const val PREFS_KEY_PULSE_DETECTED_VALUE = "pulse_detected_value"
    }
}

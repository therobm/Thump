package com.therobm.thump.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed wrapper around the Thump user-settings SharedPreferences blob.
 *
 * Owns the settings the user can change from the Settings screen — playback behaviour, cache
 * sizing, scrobble opt-out, etc. The credentials (URL / username / password / auth mode) live
 * in a separate prefs file managed by MainActivity and the playback service so they stay
 * isolated from UI settings churn.
 *
 * Reads default to the spec defaults so a clean install matches the spec exactly with no
 * extra setup.
 */
class ThumpSettings(applicationContext: Context) {

    private val prefs: SharedPreferences = applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun getPrefetchLookahead(): Int {
        return prefs.getInt(KEY_PREFETCH_LOOKAHEAD, DEFAULT_PREFETCH_LOOKAHEAD)
    }

    fun setPrefetchLookahead(value: Int) {
        prefs.edit().putInt(KEY_PREFETCH_LOOKAHEAD, value).apply()
    }

    fun getAudioCacheSizeBytes(): Long {
        return prefs.getLong(KEY_AUDIO_CACHE_SIZE_BYTES, DEFAULT_AUDIO_CACHE_SIZE_BYTES)
    }

    fun setAudioCacheSizeBytes(value: Long) {
        prefs.edit().putLong(KEY_AUDIO_CACHE_SIZE_BYTES, value).apply()
    }

    fun getScrobbleEnabled(): Boolean {
        return prefs.getBoolean(KEY_SCROBBLE_ENABLED, true)
    }

    fun setScrobbleEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_SCROBBLE_ENABLED, value).apply()
    }

    fun getNormalizeMode(): String {
        val stored: String? = prefs.getString(KEY_NORMALIZE_MODE, null)
        if (stored == null) {
            return DEFAULT_NORMALIZE_MODE
        }
        return stored
    }

    fun setNormalizeMode(value: String) {
        prefs.edit().putString(KEY_NORMALIZE_MODE, value).apply()
    }

    companion object {
        const val DEFAULT_PREFETCH_LOOKAHEAD: Int = 10
        const val MIN_PREFETCH_LOOKAHEAD: Int = 0
        const val MAX_PREFETCH_LOOKAHEAD: Int = 30

        const val DEFAULT_AUDIO_CACHE_SIZE_BYTES: Long = 500L * 1024L * 1024L
        const val MIN_AUDIO_CACHE_SIZE_BYTES: Long = 100L * 1024L * 1024L
        const val MAX_AUDIO_CACHE_SIZE_BYTES: Long = 5L * 1024L * 1024L * 1024L

        const val NORMALIZE_MODE_OFF: String = "off"
        const val NORMALIZE_MODE_TRACK: String = "track"
        const val NORMALIZE_MODE_ALBUM: String = "album"
        const val DEFAULT_NORMALIZE_MODE: String = NORMALIZE_MODE_OFF

        private const val PREFS_NAME: String = "thump_settings_user"
        private const val KEY_PREFETCH_LOOKAHEAD: String = "prefetch_lookahead"
        private const val KEY_AUDIO_CACHE_SIZE_BYTES: String = "audio_cache_size_bytes"
        private const val KEY_SCROBBLE_ENABLED: String = "scrobble_enabled"
        private const val KEY_NORMALIZE_MODE: String = "normalize_mode"
    }
}

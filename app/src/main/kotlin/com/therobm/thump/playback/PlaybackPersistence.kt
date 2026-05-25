package com.therobm.thump.playback

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * Read / write the PersistedPlaybackState SharedPreferences blob.
 *
 * Used by both the app process (PlaybackController writes on playQueue, reads on connect to
 * recover the source) and the playback service process (the service writes on transitions /
 * periodic ticks and reads on onCreate to restore the player). SharedPreferences is
 * inter-process safe in practice for atomic single-string writes.
 */
class PlaybackPersistence(applicationContext: Context) {

    private val resolvedApplicationContext: Context = applicationContext.applicationContext

    private val jsonFormat: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): PersistedPlaybackState? {
        val prefs = resolvedApplicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val stored = prefs.getString(KEY_STATE, null)
        if (stored == null || stored.isEmpty()) {
            return null
        }
        return try {
            jsonFormat.decodeFromString(PersistedPlaybackState.serializer(), stored)
        } catch (decodeFailure: Exception) {
            // Stored blob is from an older schema or corrupted — drop it and start fresh.
            null
        }
    }

    fun save(state: PersistedPlaybackState) {
        val encoded = jsonFormat.encodeToString(PersistedPlaybackState.serializer(), state)
        val prefs = resolvedApplicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit().putString(KEY_STATE, encoded).apply()
    }

    fun clear() {
        val prefs = resolvedApplicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit().remove(KEY_STATE).apply()
    }

    companion object {
        private const val PREFS_NAME: String = "thump_playback"
        private const val KEY_STATE: String = "playback_state"
    }
}

package com.therobm.thump.playback

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

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
        val stored: String? = prefs.getString(KEY_STATE, null)
        if (stored == null) {
            return null
        }
        if (stored.isEmpty()) {
            return null
        }
        val current: PersistedPlaybackState? = decodeCurrentShape(stored)
        if (current != null) {
            return current
        }
        // Either the new shape failed (e.g. coverArtId field missing from a pre-#236 blob with the
        // old streamUrl/coverArtUrl schema) or the blob is genuinely corrupt. Try the legacy
        // shape migration: extract trackId + coverArtId out of the persisted URL strings and
        // rebuild the typed state. If migration cannot recover any items, return null so the
        // caller starts with no queue rather than crashing on a malformed blob.
        return migrateLegacyShape(stored)
    }

    fun save(state: PersistedPlaybackState) {
        val encoded: String = jsonFormat.encodeToString(PersistedPlaybackState.serializer(), state)
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

    private fun decodeCurrentShape(stored: String): PersistedPlaybackState? {
        try {
            return jsonFormat.decodeFromString(PersistedPlaybackState.serializer(), stored)
        } catch (decodeFailure: Exception) {
            return null
        }
    }

    /**
     * Best-effort recovery of a queue persisted under the pre-#236 schema, where each item
     * carried `streamUrl` and `coverArtUrl` (both salted Subsonic URLs) instead of `trackId`
     * and `coverArtId`. The new shape needs the stable identifiers; this helper:
     *
     * 1. Reads the JSON as a tree.
     * 2. For each item, prefers a top-level `trackId` field if present; otherwise extracts the
     *    `id` query parameter from `streamUrl`.
     * 3. Resolves coverArtId from a top-level `coverArtId` field if present, otherwise from the
     *    `id` query parameter on `coverArtUrl`.
     * 4. Drops items that have no resolvable trackId — keeping them would persist a queue entry
     *    we cannot ever play.
     *
     * Returns null when the blob is unparseable as JSON or when zero items survive migration.
     */
    private fun migrateLegacyShape(stored: String): PersistedPlaybackState? {
        val rootElement: JsonElement
        try {
            rootElement = jsonFormat.parseToJsonElement(stored)
        } catch (parseFailure: Exception) {
            return null
        }
        if (rootElement !is JsonObject) {
            return null
        }
        val itemsElement: JsonElement? = rootElement["items"]
        if (itemsElement == null) {
            return null
        }
        if (itemsElement !is JsonArray) {
            return null
        }
        val migratedItems: ArrayList<PersistedItem> = ArrayList<PersistedItem>(itemsElement.size)
        val itemCount: Int = itemsElement.size
        for (itemIndex in 0 until itemCount) {
            val rawItem: JsonElement = itemsElement[itemIndex]
            if (rawItem !is JsonObject) {
                continue
            }
            val migratedItem: PersistedItem? = migrateSingleItem(rawItem)
            if (migratedItem == null) {
                continue
            }
            migratedItems.add(migratedItem)
        }
        if (migratedItems.isEmpty()) {
            return null
        }
        val currentIndex: Int = readIntFieldWithDefault(rootElement, "currentIndex", 0)
        val positionMs: Long = readLongFieldWithDefault(rootElement, "positionMs", 0L)
        val source: PersistedSource? = readSource(rootElement)
        val clampedIndex: Int
        if (currentIndex < 0) {
            clampedIndex = 0
        } else if (currentIndex >= migratedItems.size) {
            clampedIndex = migratedItems.size - 1
        } else {
            clampedIndex = currentIndex
        }
        return PersistedPlaybackState(
            items = migratedItems,
            currentIndex = clampedIndex,
            positionMs = positionMs,
            source = source,
        )
    }

    private fun migrateSingleItem(itemObject: JsonObject): PersistedItem? {
        val trackIdFromField: String? = readNullableStringField(itemObject, "trackId")
        val streamUrlFromField: String? = readNullableStringField(itemObject, "streamUrl")
        val resolvedTrackId: String?
        if (trackIdFromField != null && trackIdFromField.isNotEmpty()) {
            resolvedTrackId = trackIdFromField
        } else if (streamUrlFromField != null && streamUrlFromField.isNotEmpty()) {
            resolvedTrackId = extractIdQueryParameter(streamUrlFromField)
        } else {
            resolvedTrackId = null
        }
        if (resolvedTrackId == null) {
            return null
        }
        if (resolvedTrackId.isEmpty()) {
            return null
        }
        val titleFromField: String? = readNullableStringField(itemObject, "title")
        val resolvedTitle: String
        if (titleFromField == null) {
            resolvedTitle = ""
        } else {
            resolvedTitle = titleFromField
        }
        val artistFromField: String? = readNullableStringField(itemObject, "artist")
        val resolvedArtist: String
        if (artistFromField == null) {
            resolvedArtist = ""
        } else {
            resolvedArtist = artistFromField
        }
        val resolvedAlbum: String? = readNullableStringField(itemObject, "album")
        val coverArtIdFromField: String? = readNullableStringField(itemObject, "coverArtId")
        val coverArtUrlFromField: String? = readNullableStringField(itemObject, "coverArtUrl")
        val resolvedCoverArtId: String?
        if (coverArtIdFromField != null && coverArtIdFromField.isNotEmpty()) {
            resolvedCoverArtId = coverArtIdFromField
        } else if (coverArtUrlFromField != null && coverArtUrlFromField.isNotEmpty()) {
            resolvedCoverArtId = extractIdQueryParameter(coverArtUrlFromField)
        } else {
            resolvedCoverArtId = null
        }
        return PersistedItem(
            trackId = resolvedTrackId,
            title = resolvedTitle,
            artist = resolvedArtist,
            album = resolvedAlbum,
            coverArtId = resolvedCoverArtId,
        )
    }

    private fun readSource(rootElement: JsonObject): PersistedSource? {
        val sourceElement: JsonElement? = rootElement["source"]
        if (sourceElement == null) {
            return null
        }
        if (sourceElement is JsonNull) {
            return null
        }
        if (sourceElement !is JsonObject) {
            return null
        }
        val kindField: String? = readNullableStringField(sourceElement, "kind")
        val nameField: String? = readNullableStringField(sourceElement, "name")
        if (kindField == null) {
            return null
        }
        if (nameField == null) {
            return null
        }
        return PersistedSource(kind = kindField, name = nameField)
    }

    private fun readNullableStringField(holder: JsonObject, fieldName: String): String? {
        val rawElement: JsonElement? = holder[fieldName]
        if (rawElement == null) {
            return null
        }
        if (rawElement is JsonNull) {
            return null
        }
        if (rawElement !is JsonPrimitive) {
            return null
        }
        if (!rawElement.isString) {
            return null
        }
        return rawElement.content
    }

    private fun readIntFieldWithDefault(holder: JsonObject, fieldName: String, defaultValue: Int): Int {
        val rawElement: JsonElement? = holder[fieldName]
        if (rawElement == null) {
            return defaultValue
        }
        if (rawElement !is JsonPrimitive) {
            return defaultValue
        }
        try {
            return rawElement.int
        } catch (numberFailure: Exception) {
            return defaultValue
        }
    }

    private fun readLongFieldWithDefault(holder: JsonObject, fieldName: String, defaultValue: Long): Long {
        val rawElement: JsonElement? = holder[fieldName]
        if (rawElement == null) {
            return defaultValue
        }
        if (rawElement !is JsonPrimitive) {
            return defaultValue
        }
        try {
            return rawElement.long
        } catch (numberFailure: Exception) {
            return defaultValue
        }
    }

    private fun extractIdQueryParameter(url: String): String? {
        try {
            val parsed: Uri = Uri.parse(url)
            val idParameter: String? = parsed.getQueryParameter("id")
            if (idParameter == null) {
                return null
            }
            if (idParameter.isEmpty()) {
                return null
            }
            return idParameter
        } catch (uriParseFailure: Exception) {
            return null
        }
    }

    companion object {
        private const val PREFS_NAME: String = "thump_playback"
        private const val KEY_STATE: String = "playback_state"
    }
}

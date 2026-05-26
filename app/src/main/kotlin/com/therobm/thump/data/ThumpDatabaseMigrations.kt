package com.therobm.thump.data

/**
 * Ordered list of schema migrations. Index in the list is irrelevant; `targetVersion` is the
 * source of truth. Migrations apply in ascending `targetVersion` order.
 *
 * Adding a migration: append a new ThumpDatabaseMigration with the next integer version. Do
 * not edit a version that has shipped — write a new one that mutates the existing schema.
 */
internal object ThumpDatabaseMigrations {

    val ALL_MIGRATIONS: List<ThumpDatabaseMigration> = listOf(
        ThumpDatabaseMigration(
            targetVersion = 1,
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS schema_versions (
                    version INTEGER PRIMARY KEY NOT NULL,
                    applied_at_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS server_config (
                    singleton_row_id INTEGER PRIMARY KEY CHECK (singleton_row_id = 1),
                    server_url TEXT NOT NULL,
                    username TEXT NOT NULL,
                    password TEXT NOT NULL,
                    use_token_auth INTEGER NOT NULL,
                    detected_protocol TEXT,
                    last_probed_at_epoch_millis INTEGER
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS blobs (
                    blob_key TEXT PRIMARY KEY NOT NULL,
                    file_path TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    content_type TEXT,
                    fetched_at_epoch_millis INTEGER NOT NULL,
                    last_accessed_at_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
                """
                CREATE INDEX IF NOT EXISTS idx_blobs_last_accessed
                    ON blobs (last_accessed_at_epoch_millis)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS artists (
                    artist_id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    album_count INTEGER NOT NULL,
                    cover_art_id TEXT,
                    fetched_at_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS albums (
                    album_id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    artist_name TEXT,
                    artist_id TEXT,
                    year INTEGER,
                    genre TEXT,
                    duration_seconds INTEGER,
                    song_count INTEGER,
                    cover_art_id TEXT,
                    fetched_at_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
                """
                CREATE INDEX IF NOT EXISTS idx_albums_artist_id ON albums (artist_id)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS tracks (
                    track_id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    artist_name TEXT,
                    artist_id TEXT,
                    album_name TEXT,
                    album_id TEXT,
                    track_number INTEGER,
                    disc_number INTEGER,
                    year INTEGER,
                    genre TEXT,
                    duration_seconds INTEGER,
                    size_bytes INTEGER,
                    suffix TEXT,
                    content_type TEXT,
                    cover_art_id TEXT,
                    fetched_at_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
                """
                CREATE INDEX IF NOT EXISTS idx_tracks_album_id ON tracks (album_id)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS playlists (
                    playlist_id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    owner_username TEXT,
                    comment TEXT,
                    song_count INTEGER,
                    duration_seconds INTEGER,
                    cover_art_id TEXT,
                    fetched_at_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS playlist_tracks (
                    playlist_id TEXT NOT NULL,
                    sort_order INTEGER NOT NULL,
                    track_id TEXT NOT NULL,
                    PRIMARY KEY (playlist_id, sort_order)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS home_sections (
                    section_key TEXT PRIMARY KEY NOT NULL,
                    item_ids_json TEXT NOT NULL,
                    fetched_at_epoch_millis INTEGER NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS playback_queue (
                    sort_order INTEGER PRIMARY KEY NOT NULL,
                    track_id TEXT NOT NULL,
                    is_current_position INTEGER NOT NULL,
                    current_position_ms INTEGER
                )
                """.trimIndent(),
            ),
        ),
    )
}

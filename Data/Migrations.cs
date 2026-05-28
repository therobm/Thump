using System;
using System.Collections.Generic;
using Microsoft.Data.Sqlite;

namespace Thump.Data
{
	public class Migration
	{
		public int Version;
		public string Sql;

		public Migration(int version, string sql)
		{
			Version = version;
			Sql = sql;
		}
	}

	public static class Migrations
	{
		public static void Apply(SqliteConnection connection)
		{
			EnsureSchemaTable(connection);
			int currentVersion = GetCurrentVersion(connection);
			List<Migration> all = BuildMigrations();
			for (int idx = 0; idx < all.Count; idx++)
			{
				Migration migration = all[idx];
				if (migration.Version <= currentVersion)
				{
					continue;
				}
				ApplyMigration(connection, migration);
			}
		}

		private static List<Migration> BuildMigrations()
		{
			List<Migration> list = new List<Migration>();
			list.Add(new Migration(1, INITIAL_SCHEMA));
			return list;
		}

		private static void EnsureSchemaTable(SqliteConnection connection)
		{
			using (SqliteCommand cmd = connection.CreateCommand())
			{
				cmd.CommandText = "CREATE TABLE IF NOT EXISTS schema_versions (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)";
				cmd.ExecuteNonQuery();
			}
		}

		private static int GetCurrentVersion(SqliteConnection connection)
		{
			using (SqliteCommand cmd = connection.CreateCommand())
			{
				cmd.CommandText = "SELECT COALESCE(MAX(version), 0) FROM schema_versions";
				object result = cmd.ExecuteScalar();
				if (result == null)
				{
					return 0;
				}
				if (result == DBNull.Value)
				{
					return 0;
				}
				return Convert.ToInt32(result);
			}
		}

		private static void ApplyMigration(SqliteConnection connection, Migration migration)
		{
			using (SqliteTransaction tx = connection.BeginTransaction())
			{
				using (SqliteCommand cmd = connection.CreateCommand())
				{
					cmd.Transaction = tx;
					cmd.CommandText = migration.Sql;
					cmd.ExecuteNonQuery();
				}
				using (SqliteCommand insert = connection.CreateCommand())
				{
					insert.Transaction = tx;
					insert.CommandText = "INSERT INTO schema_versions (version, applied_at) VALUES ($v, $t)";
					insert.Parameters.AddWithValue("$v", migration.Version);
					insert.Parameters.AddWithValue("$t", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
					insert.ExecuteNonQuery();
				}
				tx.Commit();
			}
		}

		private const string INITIAL_SCHEMA = @"
			CREATE TABLE server_config (
				id INTEGER PRIMARY KEY CHECK (id = 1),
				url TEXT,
				username TEXT,
				password TEXT,
				last_probed INTEGER NOT NULL DEFAULT 0
			);

			CREATE TABLE artists (
				id TEXT PRIMARY KEY,
				name TEXT NOT NULL,
				cover_art TEXT,
				album_count INTEGER NOT NULL DEFAULT 0,
				play_count INTEGER NOT NULL DEFAULT 0,
				score REAL NOT NULL DEFAULT 0,
				last_played INTEGER NOT NULL DEFAULT 0,
				fetched_at INTEGER NOT NULL
			);

			CREATE TABLE albums (
				id TEXT PRIMARY KEY,
				name TEXT NOT NULL,
				artist TEXT,
				artist_id TEXT,
				cover_art TEXT,
				year INTEGER NOT NULL DEFAULT 0,
				song_count INTEGER NOT NULL DEFAULT 0,
				duration INTEGER NOT NULL DEFAULT 0,
				fetched_at INTEGER NOT NULL
			);

			CREATE INDEX idx_albums_artist_id ON albums(artist_id);

			CREATE TABLE tracks (
				id TEXT PRIMARY KEY,
				title TEXT NOT NULL,
				artist TEXT,
				artist_id TEXT,
				album TEXT,
				album_id TEXT,
				cover_art TEXT,
				duration INTEGER NOT NULL DEFAULT 0,
				fetched_at INTEGER NOT NULL
			);

			CREATE INDEX idx_tracks_album_id ON tracks(album_id);

			CREATE TABLE album_tracks (
				album_id TEXT NOT NULL,
				track_id TEXT NOT NULL,
				sort_order INTEGER NOT NULL,
				PRIMARY KEY (album_id, sort_order)
			);

			CREATE TABLE playlists (
				id TEXT PRIMARY KEY,
				name TEXT NOT NULL,
				cover_art TEXT,
				song_count INTEGER NOT NULL DEFAULT 0,
				duration INTEGER NOT NULL DEFAULT 0,
				score REAL NOT NULL DEFAULT 0,
				last_played INTEGER NOT NULL DEFAULT 0,
				fetched_at INTEGER NOT NULL
			);

			CREATE TABLE playlist_tracks (
				playlist_id TEXT NOT NULL,
				track_id TEXT NOT NULL,
				sort_order INTEGER NOT NULL,
				PRIMARY KEY (playlist_id, sort_order)
			);

			CREATE TABLE genres (
				name TEXT PRIMARY KEY,
				song_count INTEGER NOT NULL DEFAULT 0,
				album_count INTEGER NOT NULL DEFAULT 0,
				fetched_at INTEGER NOT NULL
			);

			CREATE TABLE blobs (
				blob_key TEXT PRIMARY KEY,
				file_path TEXT NOT NULL,
				size_bytes INTEGER NOT NULL,
				content_type TEXT,
				fetched_at INTEGER NOT NULL,
				last_accessed INTEGER NOT NULL
			);
		";
	}
}

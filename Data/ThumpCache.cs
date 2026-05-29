using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using Microsoft.Data.Sqlite;
using Thump.Pulse;

namespace Thump.Data
{
	public class ThumpCacheStats
	{
		public long BytesUsed;
		public int TrackCount;
		public int CoverArtCount;
		public long OldestFetchedUnix;
	}

	public class ThumpCache
	{
		private SqliteConnection m_connection;
		private Thread m_worker;
		private BlockingCollection<Action> m_queue = new BlockingCollection<Action>();
		private string m_connectionString;
		private string m_blobDirectory;
		private long m_sizeLimitBytes;

		public ThumpCache(string databasePath, string blobDirectory)
		{
			m_connectionString = "Data Source=" + databasePath;
			m_blobDirectory = blobDirectory;
			if (!Directory.Exists(m_blobDirectory))
			{
				Directory.CreateDirectory(m_blobDirectory);
			}

			m_worker = new Thread(WorkerLoop);
			m_worker.IsBackground = true;
			m_worker.Name = "ThumpCache";
			m_worker.Start();
		}

		public void Enqueue(Action work)
		{
			m_queue.Add(work);
		}

		private void WorkerLoop()
		{
			try
			{
				m_connection = new SqliteConnection(m_connectionString);
				m_connection.Open();
				ApplyPragmas();
				Migrations.Apply(m_connection);
			}
			catch (Exception ex)
			{
				Log.Exception(ex);
				System.Diagnostics.Debugger.Break();
				return;
			}

			while (true)
			{
				Action work;
				try
				{
					work = m_queue.Take();
				}
				catch (InvalidOperationException)
				{
					break;
				}
				try
				{
					work();
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
			}
		}

		private void ApplyPragmas()
		{
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "PRAGMA journal_mode=WAL;PRAGMA foreign_keys=ON;";
				cmd.ExecuteNonQuery();
			}
		}

		private static object NullableParam(string value)
		{
			if (value == null)
			{
				return DBNull.Value;
			}
			return value;
		}

		private static string ReadNullableString(SqliteDataReader reader, int column)
		{
			if (reader.IsDBNull(column))
			{
				return null;
			}
			return reader.GetString(column);
		}

		private static long ToUnixSeconds(DateTime value)
		{
			if (value == DateTime.MinValue)
			{
				return 0;
			}
			return new DateTimeOffset(value, TimeSpan.Zero).ToUnixTimeSeconds();
		}

		private static DateTime FromUnixSeconds(long seconds)
		{
			if (seconds == 0)
			{
				return DateTime.MinValue;
			}
			return DateTimeOffset.FromUnixTimeSeconds(seconds).UtcDateTime;
		}

		private static string MakeSafeFileName(string key)
		{
			char[] chars = key.ToCharArray();
			for (int idx = 0; idx < chars.Length; idx++)
			{
				char c = chars[idx];
				if (c == ':' || c == '/' || c == '\\' || c == '?' || c == '*' || c == '"' || c == '<' || c == '>' || c == '|')
				{
					chars[idx] = '_';
				}
			}
			return new string(chars);
		}

		public byte[] ReadBlob(string blobKey)
		{
			string filePath;
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT file_path FROM blobs WHERE blob_key = $k";
				cmd.Parameters.AddWithValue("$k", blobKey);
				object result = cmd.ExecuteScalar();
				if (result == null)
				{
					return null;
				}
				if (result == DBNull.Value)
				{
					return null;
				}
				filePath = (string)result;
			}
			if (!File.Exists(filePath))
			{
				using (SqliteCommand cmd = m_connection.CreateCommand())
				{
					cmd.CommandText = "DELETE FROM blobs WHERE blob_key = $k";
					cmd.Parameters.AddWithValue("$k", blobKey);
					cmd.ExecuteNonQuery();
				}
				return null;
			}
			using (SqliteCommand touch = m_connection.CreateCommand())
			{
				touch.CommandText = "UPDATE blobs SET last_accessed = $t WHERE blob_key = $k";
				touch.Parameters.AddWithValue("$t", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
				touch.Parameters.AddWithValue("$k", blobKey);
				touch.ExecuteNonQuery();
			}
			return File.ReadAllBytes(filePath);
		}

		public string GetBlobFilePath(string blobKey)
		{
			string filePath;
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT file_path FROM blobs WHERE blob_key = $k";
				cmd.Parameters.AddWithValue("$k", blobKey);
				object result = cmd.ExecuteScalar();
				if (result == null)
				{
					return null;
				}
				if (result == DBNull.Value)
				{
					return null;
				}
				filePath = (string)result;
			}
			if (!File.Exists(filePath))
			{
				using (SqliteCommand cmd = m_connection.CreateCommand())
				{
					cmd.CommandText = "DELETE FROM blobs WHERE blob_key = $k";
					cmd.Parameters.AddWithValue("$k", blobKey);
					cmd.ExecuteNonQuery();
				}
				return null;
			}
			using (SqliteCommand touch = m_connection.CreateCommand())
			{
				touch.CommandText = "UPDATE blobs SET last_accessed = $t WHERE blob_key = $k";
				touch.Parameters.AddWithValue("$t", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
				touch.Parameters.AddWithValue("$k", blobKey);
				touch.ExecuteNonQuery();
			}
			return filePath;
		}

		public void WriteBlob(string blobKey, byte[] data, string contentType)
		{
			string safeName = MakeSafeFileName(blobKey);
			string finalPath = Path.Combine(m_blobDirectory, safeName);
			string tempPath = finalPath + ".tmp";
			File.WriteAllBytes(tempPath, data);
			File.Move(tempPath, finalPath, true);

			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "INSERT OR REPLACE INTO blobs (blob_key, file_path, size_bytes, content_type, fetched_at, last_accessed) VALUES ($k, $p, $s, $c, $f, $a)";
				cmd.Parameters.AddWithValue("$k", blobKey);
				cmd.Parameters.AddWithValue("$p", finalPath);
				cmd.Parameters.AddWithValue("$s", data.Length);
				cmd.Parameters.AddWithValue("$c", NullableParam(contentType));
				cmd.Parameters.AddWithValue("$f", now);
				cmd.Parameters.AddWithValue("$a", now);
				cmd.ExecuteNonQuery();
			}
			TrimToLimit();
		}

		public ThumpCacheStats GetCacheStats()
		{
			ThumpCacheStats stats = new ThumpCacheStats();

			long pageCount;
			long pageSize;
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "PRAGMA page_count;";
				pageCount = Convert.ToInt64(cmd.ExecuteScalar());
			}
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "PRAGMA page_size;";
				pageSize = Convert.ToInt64(cmd.ExecuteScalar());
			}
			long blobBytes;
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT COALESCE(SUM(size_bytes), 0) FROM blobs";
				blobBytes = Convert.ToInt64(cmd.ExecuteScalar());
			}
			stats.BytesUsed = (pageCount * pageSize) + blobBytes;

			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT COUNT(*) FROM tracks";
				stats.TrackCount = Convert.ToInt32(cmd.ExecuteScalar());
			}
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT COUNT(*) FROM blobs WHERE content_type LIKE 'image/%'";
				stats.CoverArtCount = Convert.ToInt32(cmd.ExecuteScalar());
			}
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT COALESCE(MIN(fetched_at), 0) FROM (SELECT fetched_at FROM tracks WHERE fetched_at > 0 UNION ALL SELECT fetched_at FROM albums WHERE fetched_at > 0 UNION ALL SELECT fetched_at FROM artists WHERE fetched_at > 0 UNION ALL SELECT fetched_at FROM playlists WHERE fetched_at > 0 UNION ALL SELECT fetched_at FROM blobs WHERE fetched_at > 0)";
				stats.OldestFetchedUnix = Convert.ToInt64(cmd.ExecuteScalar());
			}
			return stats;
		}

		public void ClearCache()
		{
			List<string> filePaths = new List<string>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT file_path FROM blobs";
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						filePaths.Add(reader.GetString(0));
					}
				}
			}
			for (int idx = 0; idx < filePaths.Count; idx++)
			{
				if (File.Exists(filePaths[idx]))
				{
					File.Delete(filePaths[idx]);
				}
			}
			string[] tables = new string[] { "blobs", "album_tracks", "playlist_tracks", "tracks", "albums", "playlists", "artists" };
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				for (int idx = 0; idx < tables.Length; idx++)
				{
					using (SqliteCommand cmd = m_connection.CreateCommand())
					{
						cmd.Transaction = tx;
						cmd.CommandText = "DELETE FROM " + tables[idx];
						cmd.ExecuteNonQuery();
					}
				}
				tx.Commit();
			}
		}

		public void SetSizeLimitBytes(long limitBytes)
		{
			m_sizeLimitBytes = limitBytes;
		}

		private void TrimToLimit()
		{
			if (m_sizeLimitBytes <= 0)
			{
				return;
			}
			long total;
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT COALESCE(SUM(size_bytes), 0) FROM blobs";
				total = Convert.ToInt64(cmd.ExecuteScalar());
			}
			if (total <= m_sizeLimitBytes)
			{
				return;
			}
			List<string> keys = new List<string>();
			List<string> paths = new List<string>();
			List<long> sizes = new List<long>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT blob_key, file_path, size_bytes FROM blobs ORDER BY last_accessed ASC";
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						keys.Add(reader.GetString(0));
						paths.Add(reader.GetString(1));
						sizes.Add(reader.GetInt64(2));
					}
				}
			}
			for (int idx = 0; idx < keys.Count; idx++)
			{
				if (total <= m_sizeLimitBytes)
				{
					return;
				}
				if (File.Exists(paths[idx]))
				{
					File.Delete(paths[idx]);
				}
				using (SqliteCommand cmd = m_connection.CreateCommand())
				{
					cmd.CommandText = "DELETE FROM blobs WHERE blob_key = $k";
					cmd.Parameters.AddWithValue("$k", keys[idx]);
					cmd.ExecuteNonQuery();
				}
				total = total - sizes[idx];
			}
		}

		public List<PulseArtist> GetAllArtists()
		{
			List<PulseArtist> result = new List<PulseArtist>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT id, name, cover_art, album_count, play_count, score, last_played FROM artists ORDER BY name";
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						PulseArtist artist = new PulseArtist();
						artist.Id = reader.GetString(0);
						artist.Name = reader.GetString(1);
						artist.CoverArt = ReadNullableString(reader, 2);
						artist.AlbumCount = reader.GetInt32(3);
						artist.PlayCount = reader.GetInt32(4);
						artist.Score = (float)reader.GetDouble(5);
						artist.LastPlayed = FromUnixSeconds(reader.GetInt64(6));
						result.Add(artist);
					}
				}
			}
			return result;
		}

		public void UpdateAllArtists(List<PulseArtist> artists)
		{
			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				using (SqliteCommand del = m_connection.CreateCommand())
				{
					del.Transaction = tx;
					del.CommandText = "DELETE FROM artists";
					del.ExecuteNonQuery();
				}
				for (int idx = 0; idx < artists.Count; idx++)
				{
					PulseArtist artist = artists[idx];
					using (SqliteCommand ins = m_connection.CreateCommand())
					{
						ins.Transaction = tx;
						ins.CommandText = "INSERT INTO artists (id, name, cover_art, album_count, play_count, score, last_played, fetched_at) VALUES ($id, $name, $ca, $ac, $pc, $score, $lp, $f)";
						ins.Parameters.AddWithValue("$id", artist.Id);
						ins.Parameters.AddWithValue("$name", artist.Name);
						ins.Parameters.AddWithValue("$ca", NullableParam(artist.CoverArt));
						ins.Parameters.AddWithValue("$ac", artist.AlbumCount);
						ins.Parameters.AddWithValue("$pc", artist.PlayCount);
						ins.Parameters.AddWithValue("$score", artist.Score);
						ins.Parameters.AddWithValue("$lp", ToUnixSeconds(artist.LastPlayed));
						ins.Parameters.AddWithValue("$f", now);
						ins.ExecuteNonQuery();
					}
				}
				tx.Commit();
			}
		}

		public List<PulseAlbum> GetAlbumsForArtist(string artistId)
		{
			List<PulseAlbum> result = new List<PulseAlbum>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT id, name, artist, artist_id, cover_art, year, song_count, duration FROM albums WHERE artist_id = $aid ORDER BY year, name";
				cmd.Parameters.AddWithValue("$aid", artistId);
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						result.Add(ReadAlbumRow(reader));
					}
				}
			}
			return result;
		}

		public void UpdateAlbumsForArtist(string artistId, List<PulseAlbum> albums)
		{

			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				List<string> existingIds = new List<string>();
				using (SqliteCommand sel = m_connection.CreateCommand())
				{
					sel.Transaction = tx;
					sel.CommandText = "SELECT id FROM albums WHERE artist_id = $aid";
					sel.Parameters.AddWithValue("$aid", artistId);
					using (SqliteDataReader r = sel.ExecuteReader())
					{
						while (r.Read())
						{
							existingIds.Add(r.GetString(0));
						}
					}
				}
				for (int idx = 0; idx < existingIds.Count; idx++)
				{
					using (SqliteCommand atDel = m_connection.CreateCommand())
					{
						atDel.Transaction = tx;
						atDel.CommandText = "DELETE FROM album_tracks WHERE album_id = $id";
						atDel.Parameters.AddWithValue("$id", existingIds[idx]);
						atDel.ExecuteNonQuery();
					}
				}
				using (SqliteCommand del = m_connection.CreateCommand())
				{
					del.Transaction = tx;
					del.CommandText = "DELETE FROM albums WHERE artist_id = $aid";
					del.Parameters.AddWithValue("$aid", artistId);
					del.ExecuteNonQuery();
				}
				for (int idx = 0; idx < albums.Count; idx++)
				{
					WriteAlbumRow(tx, albums[idx], now);
				}
				tx.Commit();
			}
		}

		public T ReadData<T>(string id) where T : ThumpDataOb
		{
			return	null;
		}
		
		public List<T> ReadDataList<T>(string id) where T : ThumpDataOb
		{
			return	null;
		}

		public PulseAlbum GetAlbum(string albumId)
		{
			PulseAlbum album = new PulseAlbum();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT id, name, artist, artist_id, cover_art, year, song_count, duration FROM albums WHERE id = $id";
				cmd.Parameters.AddWithValue("$id", albumId);
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					if (!reader.Read())
					{
						return album;
					}
					album = ReadAlbumRow(reader);
				}
			}
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT t.id, t.title, t.artist, t.artist_id, t.album, t.album_id, t.cover_art, t.duration FROM album_tracks at JOIN tracks t ON t.id = at.track_id WHERE at.album_id = $id ORDER BY at.sort_order";
				cmd.Parameters.AddWithValue("$id", albumId);
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						album.Songs.Add(ReadSongRow(reader));
					}
				}
			}
			return album;
		}

		public void UpdateAlbum(string albumId, PulseAlbum album)
		{
			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				WriteAlbumRow(tx, album, now);
				using (SqliteCommand del = m_connection.CreateCommand())
				{
					del.Transaction = tx;
					del.CommandText = "DELETE FROM album_tracks WHERE album_id = $id";
					del.Parameters.AddWithValue("$id", album.Id);
					del.ExecuteNonQuery();
				}
				for (int idx = 0; idx < album.Songs.Count; idx++)
				{
					PulseTrack song = album.Songs[idx];
					WriteTrackRow(tx, song, now);
					using (SqliteCommand atIns = m_connection.CreateCommand())
					{
						atIns.Transaction = tx;
						atIns.CommandText = "INSERT INTO album_tracks (album_id, track_id, sort_order) VALUES ($aid, $tid, $o)";
						atIns.Parameters.AddWithValue("$aid", album.Id);
						atIns.Parameters.AddWithValue("$tid", song.Id);
						atIns.Parameters.AddWithValue("$o", idx);
						atIns.ExecuteNonQuery();
					}
				}
				tx.Commit();
			}
		}

		public List<PulsePlaylist> GetAllPlaylists()
		{
			List<PulsePlaylist> result = new List<PulsePlaylist>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT id, name, cover_art, song_count, duration, score, last_played FROM playlists ORDER BY name";
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						result.Add(ReadPlaylistRow(reader));
					}
				}
			}
			return result;
		}

		public void UpdateAllPlaylists(List<PulsePlaylist> playlists)
		{
			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				using (SqliteCommand del = m_connection.CreateCommand())
				{
					del.Transaction = tx;
					del.CommandText = "DELETE FROM playlists";
					del.ExecuteNonQuery();
				}
				for (int idx = 0; idx < playlists.Count; idx++)
				{
					WritePlaylistRow(tx, playlists[idx], now);
				}
				tx.Commit();
			}
		}

		public PulsePlaylist GetPlaylist(string playlistId)
		{
			PulsePlaylist playlist = new PulsePlaylist();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT id, name, cover_art, song_count, duration, score, last_played FROM playlists WHERE id = $id";
				cmd.Parameters.AddWithValue("$id", playlistId);
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					if (!reader.Read())
					{
						return playlist;
					}
					playlist = ReadPlaylistRow(reader);
				}
			}
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT t.id, t.title, t.artist, t.artist_id, t.album, t.album_id, t.cover_art, t.duration FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id WHERE pt.playlist_id = $id ORDER BY pt.sort_order";
				cmd.Parameters.AddWithValue("$id", playlistId);
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						playlist.Songs.Add(ReadSongRow(reader));
					}
				}
			}
			return playlist;
		}
		public void UpdatePlaylist(string id, PulsePlaylist playlist)
		{
			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				WritePlaylistRow(tx, playlist, now);
				using (SqliteCommand del = m_connection.CreateCommand())
				{
					del.Transaction = tx;
					del.CommandText = "DELETE FROM playlist_tracks WHERE playlist_id = $id";
					del.Parameters.AddWithValue("$id", playlist.Id);
					del.ExecuteNonQuery();
				}
				for (int idx = 0; idx < playlist.Songs.Count; idx++)
				{
					PulseTrack song = playlist.Songs[idx];
					WriteTrackRow(tx, song, now);
					using (SqliteCommand ptIns = m_connection.CreateCommand())
					{
						ptIns.Transaction = tx;
						ptIns.CommandText = "INSERT INTO playlist_tracks (playlist_id, track_id, sort_order) VALUES ($pid, $tid, $o)";
						ptIns.Parameters.AddWithValue("$pid", playlist.Id);
						ptIns.Parameters.AddWithValue("$tid", song.Id);
						ptIns.Parameters.AddWithValue("$o", idx);
						ptIns.ExecuteNonQuery();
					}
				}
				tx.Commit();
			}
		}
		public byte[] GetCoverArt(string coverArtId)
		{
			if (string.IsNullOrEmpty(coverArtId))
			{
				return null;
			}
			return ReadBlob("coverart:" + coverArtId);
		}
		public void UpdateCoverArt(string coverArtId, byte[] data)
		{
			if (string.IsNullOrEmpty(coverArtId) || data == null || data.Length == 0)
			{
				return;
			}
			WriteBlob("coverart:" + coverArtId, data, DetectImageContentType(data));
		}

		private static string DetectImageContentType(byte[] data)
		{
			if (data.Length >= 4 && data[0] == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47)
			{
				return "image/png";
			}
			if (data.Length >= 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF)
			{
				return "image/jpeg";
			}
			if (data.Length >= 4 && data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x38)
			{
				return "image/gif";
			}
			if (data.Length >= 12 && data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46 && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50)
			{
				return "image/webp";
			}
			return "image/jpeg";
		}

		public List<PulseAlbum> GetAlbums()
		{
			List<PulseAlbum> result = new List<PulseAlbum>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT id, name, artist, artist_id, cover_art, year, song_count, duration FROM albums ORDER BY name";
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						result.Add(ReadAlbumRow(reader));
					}
				}
			}
			return result;
		}
		public void UpdateAlbums(List<PulseAlbum> albums)
		{
			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				using (SqliteCommand del = m_connection.CreateCommand())
				{
					del.Transaction = tx;
					del.CommandText = "DELETE FROM albums";
					del.ExecuteNonQuery();
				}
				for (int idx = 0; idx < albums.Count; idx++)
				{
					WriteAlbumRow(tx, albums[idx], now);
				}
				tx.Commit();
			}
		}

		public List<PulseObject> GetRecentlyAdded()
		{
			List<PulseObject> result = new List<PulseObject>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT item_type, item_id, name, title, artist, artist_id, album, album_id, cover_art, year, song_count, album_count, play_count, duration, score, last_played FROM home_sections WHERE section_key = $key ORDER BY position";
				cmd.Parameters.AddWithValue("$key", "recently_added");
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						PulseObject ob = ReadHomeSectionObject(reader);
						if (ob != null)
						{
							result.Add(ob);
						}
					}
				}
			}
			return result;
		}
		public void UpdateRecentlyAdded(List<PulseObject> albums)
		{
			ReplaceHomeSection("recently_added", albums);
		}

		public List<PulseGenre> GetGenres()
		{
			List<PulseGenre> result = new List<PulseGenre>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT name, song_count, album_count FROM genres ORDER BY name";
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						PulseGenre genre = new PulseGenre();
						genre.Id = reader.GetString(0);
						genre.Name = reader.GetString(0);
						genre.SongCount = reader.GetInt32(1);
						genre.AlbumCount = reader.GetInt32(2);
						result.Add(genre);
					}
				}
			}
			return result;
		}
		public void UpdateGenres(List<PulseGenre> genres)
		{
			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				using (SqliteCommand del = m_connection.CreateCommand())
				{
					del.Transaction = tx;
					del.CommandText = "DELETE FROM genres";
					del.ExecuteNonQuery();
				}
				for (int idx = 0; idx < genres.Count; idx++)
				{
					PulseGenre genre = genres[idx];
					using (SqliteCommand ins = m_connection.CreateCommand())
					{
						ins.Transaction = tx;
						ins.CommandText = "INSERT INTO genres (name, song_count, album_count, fetched_at) VALUES ($name, $sc, $ac, $f)";
						ins.Parameters.AddWithValue("$name", genre.Name);
						ins.Parameters.AddWithValue("$sc", genre.SongCount);
						ins.Parameters.AddWithValue("$ac", genre.AlbumCount);
						ins.Parameters.AddWithValue("$f", now);
						ins.ExecuteNonQuery();
					}
				}
				tx.Commit();
			}
		}

		public List<PulseTrack> GetTracksForGenre(string genre)
		{
			List<PulseTrack> result = new List<PulseTrack>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT t.id, t.title, t.artist, t.artist_id, t.album, t.album_id, t.cover_art, t.duration FROM genre_tracks gt JOIN tracks t ON t.id = gt.track_id WHERE gt.genre = $g ORDER BY gt.sort_order";
				cmd.Parameters.AddWithValue("$g", genre);
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						result.Add(ReadSongRow(reader));
					}
				}
			}
			return result;
		}
		public void UpdateTracksForGenre(string genre, List<PulseTrack> tracks)
		{
			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				using (SqliteCommand del = m_connection.CreateCommand())
				{
					del.Transaction = tx;
					del.CommandText = "DELETE FROM genre_tracks WHERE genre = $g";
					del.Parameters.AddWithValue("$g", genre);
					del.ExecuteNonQuery();
				}
				for (int idx = 0; idx < tracks.Count; idx++)
				{
					PulseTrack song = tracks[idx];
					WriteTrackRow(tx, song, now);
					using (SqliteCommand gtIns = m_connection.CreateCommand())
					{
						gtIns.Transaction = tx;
						gtIns.CommandText = "INSERT INTO genre_tracks (genre, track_id, sort_order) VALUES ($g, $tid, $o)";
						gtIns.Parameters.AddWithValue("$g", genre);
						gtIns.Parameters.AddWithValue("$tid", song.Id);
						gtIns.Parameters.AddWithValue("$o", idx);
						gtIns.ExecuteNonQuery();
					}
				}
				tx.Commit();
			}
		}

		public List<PulseObject> GetRecentlyPlayed()
		{
			List<PulseObject> result = new List<PulseObject>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT item_type, item_id, name, title, artist, artist_id, album, album_id, cover_art, year, song_count, album_count, play_count, duration, score, last_played FROM home_sections WHERE section_key = $key ORDER BY position";
				cmd.Parameters.AddWithValue("$key", "recently_played");
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						PulseObject ob = ReadHomeSectionObject(reader);
						if (ob != null)
						{
							result.Add(ob);
						}
					}
				}
			}
			return result;
		}
		public void UpdateRecentlyPlayed(List<PulseObject> tracks)
		{
			ReplaceHomeSection("recently_played", tracks);
		}

		public List<PulsePlaylist> GetTopPlaylists()
		{
			List<PulsePlaylist> result = new List<PulsePlaylist>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT item_type, item_id, name, title, artist, artist_id, album, album_id, cover_art, year, song_count, album_count, play_count, duration, score, last_played FROM home_sections WHERE section_key = $key ORDER BY position";
				cmd.Parameters.AddWithValue("$key", "top_playlists");
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						PulseObject ob = ReadHomeSectionObject(reader);
						if (ob != null && ob.Kind == eDataType.Playlist)
						{
							result.Add((PulsePlaylist)ob);
						}
					}
				}
			}
			return result;
		}
		public void UpdateTopPlaylists(List<PulsePlaylist> playlists)
		{
			List<PulseObject> tmp = new List<PulseObject>();
			for (int idx = 0; idx < playlists.Count; idx++)
			{
				tmp.Add(playlists[idx]);
			}
			ReplaceHomeSection("top_playlists", tmp);
		}

		public List<PulseArtist> GetPopularArtists()
		{
			List<PulseArtist> result = new List<PulseArtist>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT item_type, item_id, name, title, artist, artist_id, album, album_id, cover_art, year, song_count, album_count, play_count, duration, score, last_played FROM home_sections WHERE section_key = $key ORDER BY position";
				cmd.Parameters.AddWithValue("$key", "popular_artists");
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						PulseObject ob = ReadHomeSectionObject(reader);
						if (ob != null && ob.Kind == eDataType.Artist)
						{
							result.Add((PulseArtist)ob);
						}
					}
				}
			}
			return result;
		}
		public void UpdatePopularArtists(List<PulseArtist> artists)
		{
			List<PulseObject> tmp = new List<PulseObject>();
			for (int idx = 0; idx < artists.Count; idx++)
			{
				tmp.Add(artists[idx]);
			}
			ReplaceHomeSection("popular_artists", tmp);
		}

		public List<PulseTrack> GetStarred()
		{
			List<PulseTrack> result = new List<PulseTrack>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT item_type, item_id, name, title, artist, artist_id, album, album_id, cover_art, year, song_count, album_count, play_count, duration, score, last_played FROM home_sections WHERE section_key = $key ORDER BY position";
				cmd.Parameters.AddWithValue("$key", "starred");
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						PulseObject ob = ReadHomeSectionObject(reader);
						if (ob != null && ob.Kind == eDataType.Track)
						{
							result.Add((PulseTrack)ob);
						}
					}
				}
			}
			return result;
		}
		public void UpdateStarred(List<PulseTrack> tracks)
		{
			List<PulseObject> tmp = new List<PulseObject>();
			for (int idx = 0; idx < tracks.Count; idx++)
			{
				tmp.Add(tracks[idx]);
			}
			ReplaceHomeSection("starred", tmp);
		}

		public List<PulseTrack> GetFavorites()
		{
			List<PulseTrack> result = new List<PulseTrack>();
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.CommandText = "SELECT item_type, item_id, name, title, artist, artist_id, album, album_id, cover_art, year, song_count, album_count, play_count, duration, score, last_played FROM home_sections WHERE section_key = $key ORDER BY position";
				cmd.Parameters.AddWithValue("$key", "favorites");
				using (SqliteDataReader reader = cmd.ExecuteReader())
				{
					while (reader.Read())
					{
						PulseObject ob = ReadHomeSectionObject(reader);
						if (ob != null && ob.Kind == eDataType.Track)
						{
							result.Add((PulseTrack)ob);
						}
					}
				}
			}
			return result;
		}
		public void UpdateFavorites(List<PulseTrack> songs)
		{
			List<PulseObject> tmp = new List<PulseObject>();
			for (int idx = 0; idx < songs.Count; idx++)
			{
				tmp.Add(songs[idx]);
			}
			ReplaceHomeSection("favorites", tmp);
		}
		private static PulseAlbum ReadAlbumRow(SqliteDataReader reader)
		{
			PulseAlbum album = new PulseAlbum();
			album.Id = reader.GetString(0);
			album.Name = reader.GetString(1);
			album.Artist = ReadNullableString(reader, 2);
			album.ArtistId = ReadNullableString(reader, 3);
			album.CoverArt = ReadNullableString(reader, 4);
			album.Year = reader.GetInt32(5);
			album.SongCount = reader.GetInt32(6);
			album.Duration = reader.GetInt32(7);
			return album;
		}

		private static PulseTrack ReadSongRow(SqliteDataReader reader)
		{
			PulseTrack song = new PulseTrack();
			song.Id = reader.GetString(0);
			song.Title = reader.GetString(1);
			song.Artist = ReadNullableString(reader, 2);
			song.ArtistId = ReadNullableString(reader, 3);
			song.Album = ReadNullableString(reader, 4);
			song.AlbumId = ReadNullableString(reader, 5);
			song.CoverArt = ReadNullableString(reader, 6);
			song.Duration = reader.GetInt32(7);
			return song;
		}

		private static PulsePlaylist ReadPlaylistRow(SqliteDataReader reader)
		{
			PulsePlaylist playlist = new PulsePlaylist();
			playlist.Id = reader.GetString(0);
			playlist.Name = reader.GetString(1);
			playlist.CoverArt = ReadNullableString(reader, 2);
			playlist.SongCount = reader.GetInt32(3);
			playlist.Duration = reader.GetInt32(4);
			playlist.Score = (float)reader.GetDouble(5);
			playlist.LastPlayed = FromUnixSeconds(reader.GetInt64(6));
			return playlist;
		}

		private void WriteAlbumRow(SqliteTransaction tx, PulseAlbum album, long now)
		{
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.Transaction = tx;
				cmd.CommandText = "INSERT OR REPLACE INTO albums (id, name, artist, artist_id, cover_art, year, song_count, duration, fetched_at) VALUES ($id, $n, $a, $aid, $ca, $y, $sc, $d, $f)";
				cmd.Parameters.AddWithValue("$id", album.Id);
				cmd.Parameters.AddWithValue("$n", album.Name);
				cmd.Parameters.AddWithValue("$a", NullableParam(album.Artist));
				cmd.Parameters.AddWithValue("$aid", NullableParam(album.ArtistId));
				cmd.Parameters.AddWithValue("$ca", NullableParam(album.CoverArt));
				cmd.Parameters.AddWithValue("$y", album.Year);
				cmd.Parameters.AddWithValue("$sc", album.SongCount);
				cmd.Parameters.AddWithValue("$d", album.Duration);
				cmd.Parameters.AddWithValue("$f", now);
				cmd.ExecuteNonQuery();
			}
		}

		private void WriteTrackRow(SqliteTransaction tx, PulseTrack song, long now)
		{
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.Transaction = tx;
				cmd.CommandText = "INSERT OR REPLACE INTO tracks (id, title, artist, artist_id, album, album_id, cover_art, duration, fetched_at) VALUES ($id, $t, $a, $aid, $alb, $albid, $ca, $d, $f)";
				cmd.Parameters.AddWithValue("$id", song.Id);
				cmd.Parameters.AddWithValue("$t", song.Title);
				cmd.Parameters.AddWithValue("$a", NullableParam(song.Artist));
				cmd.Parameters.AddWithValue("$aid", NullableParam(song.ArtistId));
				cmd.Parameters.AddWithValue("$alb", NullableParam(song.Album));
				cmd.Parameters.AddWithValue("$albid", NullableParam(song.AlbumId));
				cmd.Parameters.AddWithValue("$ca", NullableParam(song.ImageID));
				cmd.Parameters.AddWithValue("$d", song.Duration);
				cmd.Parameters.AddWithValue("$f", now);
				cmd.ExecuteNonQuery();
			}
		}

		private void WritePlaylistRow(SqliteTransaction tx, PulsePlaylist playlist, long now)
		{
			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.Transaction = tx;
				cmd.CommandText = "INSERT OR REPLACE INTO playlists (id, name, cover_art, song_count, duration, score, last_played, fetched_at) VALUES ($id, $n, $ca, $sc, $d, $s, $lp, $f)";
				cmd.Parameters.AddWithValue("$id", playlist.Id);
				cmd.Parameters.AddWithValue("$n", playlist.Name);
				cmd.Parameters.AddWithValue("$ca", NullableParam(playlist.CoverArt));
				cmd.Parameters.AddWithValue("$sc", playlist.SongCount);
				cmd.Parameters.AddWithValue("$d", playlist.Duration);
				cmd.Parameters.AddWithValue("$s", playlist.Score);
				cmd.Parameters.AddWithValue("$lp", ToUnixSeconds(playlist.LastPlayed));
				cmd.Parameters.AddWithValue("$f", now);
				cmd.ExecuteNonQuery();
			}
		}

		private void WriteHomeSectionRow(SqliteTransaction tx, string sectionKey, int position, PulseObject item, long now)
		{
			string itemType = "";
			object itemId = NullableParam(null);
			object name = NullableParam(null);
			object title = NullableParam(null);
			object artist = NullableParam(null);
			object artistId = NullableParam(null);
			object album = NullableParam(null);
			object albumId = NullableParam(null);
			object coverArt = NullableParam(null);
			int year = 0;
			int songCount = 0;
			int albumCount = 0;
			int playCount = 0;
			int duration = 0;
			float score = 0;
			long lastPlayed = 0;

			switch (item.Kind)
			{
				case eDataType.Album:
				{
					PulseAlbum a = (PulseAlbum)item;
					itemType = "album";
					itemId = NullableParam(a.Id);
					name = NullableParam(a.Name);
					artist = NullableParam(a.Artist);
					artistId = NullableParam(a.ArtistId);
					coverArt = NullableParam(a.CoverArt);
					year = a.Year;
					songCount = a.SongCount;
					duration = a.Duration;
					break;
				}
				case eDataType.Track:
				{
					PulseTrack t = (PulseTrack)item;
					itemType = "track";
					itemId = NullableParam(t.Id);
					title = NullableParam(t.Title);
					artist = NullableParam(t.Artist);
					artistId = NullableParam(t.ArtistId);
					album = NullableParam(t.Album);
					albumId = NullableParam(t.AlbumId);
					coverArt = NullableParam(t.ImageID);
					duration = t.Duration;
					break;
				}
				case eDataType.Playlist:
				{
					PulsePlaylist p = (PulsePlaylist)item;
					itemType = "playlist";
					itemId = NullableParam(p.Id);
					name = NullableParam(p.Name);
					coverArt = NullableParam(p.CoverArt);
					songCount = p.SongCount;
					duration = p.Duration;
					score = p.Score;
					lastPlayed = ToUnixSeconds(p.LastPlayed);
					break;
				}
				case eDataType.Artist:
				{
					PulseArtist ar = (PulseArtist)item;
					itemType = "artist";
					itemId = NullableParam(ar.Id);
					name = NullableParam(ar.Name);
					coverArt = NullableParam(ar.CoverArt);
					albumCount = ar.AlbumCount;
					playCount = ar.PlayCount;
					score = ar.Score;
					lastPlayed = ToUnixSeconds(ar.LastPlayed);
					break;
				}
			}

			using (SqliteCommand cmd = m_connection.CreateCommand())
			{
				cmd.Transaction = tx;
				cmd.CommandText = "INSERT INTO home_sections (section_key, position, item_type, item_id, name, title, artist, artist_id, album, album_id, cover_art, year, song_count, album_count, play_count, duration, score, last_played, fetched_at) VALUES ($key, $pos, $it, $iid, $name, $title, $artist, $aid, $album, $albid, $ca, $y, $sc, $ac, $pc, $d, $score, $lp, $f)";
				cmd.Parameters.AddWithValue("$key", sectionKey);
				cmd.Parameters.AddWithValue("$pos", position);
				cmd.Parameters.AddWithValue("$it", itemType);
				cmd.Parameters.AddWithValue("$iid", itemId);
				cmd.Parameters.AddWithValue("$name", name);
				cmd.Parameters.AddWithValue("$title", title);
				cmd.Parameters.AddWithValue("$artist", artist);
				cmd.Parameters.AddWithValue("$aid", artistId);
				cmd.Parameters.AddWithValue("$album", album);
				cmd.Parameters.AddWithValue("$albid", albumId);
				cmd.Parameters.AddWithValue("$ca", coverArt);
				cmd.Parameters.AddWithValue("$y", year);
				cmd.Parameters.AddWithValue("$sc", songCount);
				cmd.Parameters.AddWithValue("$ac", albumCount);
				cmd.Parameters.AddWithValue("$pc", playCount);
				cmd.Parameters.AddWithValue("$d", duration);
				cmd.Parameters.AddWithValue("$score", score);
				cmd.Parameters.AddWithValue("$lp", lastPlayed);
				cmd.Parameters.AddWithValue("$f", now);
				cmd.ExecuteNonQuery();
			}
		}

		private PulseObject ReadHomeSectionObject(SqliteDataReader reader)
		{
			string itemType = reader.GetString(0);
			switch (itemType)
			{
				case "album":
				{
					PulseAlbum a = new PulseAlbum();
					a.Id = ReadNullableString(reader, 1);
					a.Name = ReadNullableString(reader, 2);
					a.Artist = ReadNullableString(reader, 4);
					a.ArtistId = ReadNullableString(reader, 5);
					a.CoverArt = ReadNullableString(reader, 8);
					a.Year = reader.GetInt32(9);
					a.SongCount = reader.GetInt32(10);
					a.Duration = reader.GetInt32(13);
					return a;
				}
				case "track":
				{
					PulseTrack t = new PulseTrack();
					t.Id = ReadNullableString(reader, 1);
					t.Title = ReadNullableString(reader, 3);
					t.Artist = ReadNullableString(reader, 4);
					t.ArtistId = ReadNullableString(reader, 5);
					t.Album = ReadNullableString(reader, 6);
					t.AlbumId = ReadNullableString(reader, 7);
					t.CoverArt = ReadNullableString(reader, 8);
					t.Duration = reader.GetInt32(13);
					return t;
				}
				case "playlist":
				{
					PulsePlaylist p = new PulsePlaylist();
					p.Id = ReadNullableString(reader, 1);
					p.Name = ReadNullableString(reader, 2);
					p.CoverArt = ReadNullableString(reader, 8);
					p.SongCount = reader.GetInt32(10);
					p.Duration = reader.GetInt32(13);
					p.Score = (float)reader.GetDouble(14);
					p.LastPlayed = FromUnixSeconds(reader.GetInt64(15));
					return p;
				}
				case "artist":
				{
					PulseArtist ar = new PulseArtist();
					ar.Id = ReadNullableString(reader, 1);
					ar.Name = ReadNullableString(reader, 2);
					ar.CoverArt = ReadNullableString(reader, 8);
					ar.AlbumCount = reader.GetInt32(11);
					ar.PlayCount = reader.GetInt32(12);
					ar.Score = (float)reader.GetDouble(14);
					ar.LastPlayed = FromUnixSeconds(reader.GetInt64(15));
					return ar;
				}
				default:
				{
					return null;
				}
			}
		}

		private void ReplaceHomeSection(string sectionKey, List<PulseObject> items)
		{
			long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			using (SqliteTransaction tx = m_connection.BeginTransaction())
			{
				using (SqliteCommand del = m_connection.CreateCommand())
				{
					del.Transaction = tx;
					del.CommandText = "DELETE FROM home_sections WHERE section_key = $key";
					del.Parameters.AddWithValue("$key", sectionKey);
					del.ExecuteNonQuery();
				}
				for (int idx = 0; idx < items.Count; idx++)
				{
					WriteHomeSectionRow(tx, sectionKey, idx, items[idx], now);
				}
				tx.Commit();
			}
		}
	}
}

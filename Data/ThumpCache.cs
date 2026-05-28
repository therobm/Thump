using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using Microsoft.Data.Sqlite;
using Thump.Pulse;

namespace Thump.Data
{
	public class ThumpCache
	{
		private SqliteConnection m_connection;
		private Thread m_worker;
		private BlockingCollection<Action> m_queue = new BlockingCollection<Action>();
		private string m_connectionString;
		private string m_blobDirectory;

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
			if (File.Exists(finalPath))
			{
				File.Delete(finalPath);
			}
			File.Move(tempPath, finalPath);

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
			return null;
		}
		public void UpdateCoverArt(string coverArtId, byte[] data)
		{
		}

		public List<PulseAlbum> GetAlbums()
		{
			return new List<PulseAlbum>();
		}
		public void UpdateAlbums(List<PulseAlbum> albums)
		{
		}

		public List<PulseObject> GetRecentlyAdded()
		{
			return new List<PulseObject>();
		}
		public void UpdateRecentlyAdded(List<PulseObject> albums)
		{
		}

		public List<PulseGenre> GetGenres()
		{
			return new List<PulseGenre>();
		}
		public void UpdateGenres(List<PulseGenre> genres)
		{
		}

		public List<PulseTrack> GetTracksForGenre(string genre)
		{
			return new List<PulseTrack>();
		}
		public void UpdateTracksForGenre(string genre, List<PulseTrack> tracks)
		{
		}

		public List<PulseObject> GetRecentlyPlayed()
		{
			return new List<PulseObject>();
		}
		public void UpdateRecentlyPlayed(List<PulseObject> tracks)
		{
		}

		public List<PulsePlaylist> GetTopPlaylists()
		{
			return new List<PulsePlaylist>();
		}
		public void UpdateTopPlaylists(List<PulsePlaylist> playlists)
		{
		}

		public List<PulseArtist> GetPopularArtists()
		{
			return new List<PulseArtist>();
		}
		public void UpdatePopularArtists(List<PulseArtist> artists)
		{
		}

		public List<PulseTrack> GetStarred()
		{
			return new List<PulseTrack>();
		}
		public void UpdateStarred(List<PulseTrack> tracks)
		{
		}

		public List<PulseTrack> GetFavorites()
		{
			return new List<PulseTrack>();
		}
		public void UpdateFavorites(List<PulseTrack> songs)
		{
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
				cmd.Parameters.AddWithValue("$ca", DBNull.Value);
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
	}
}

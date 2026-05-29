using Microsoft.Data.Sqlite;
using System;
using System.Collections.Generic;
using System.Text;
using Thump.Pulse;

namespace Thump.Utility
{
	public static class SqlHelper
	{
		public static object NullableParam(string value)
		{
			if (value == null)
			{
				return DBNull.Value;
			}
			return value;
		}

		public static string ReadNullableString(SqliteDataReader reader, int column)
		{
			if (reader.IsDBNull(column))
			{
				return null;
			}
			return reader.GetString(column);
		}

		public static long ToUnixSeconds(DateTime value)
		{
			if (value == DateTime.MinValue)
			{
				return 0;
			}
			return new DateTimeOffset(value, TimeSpan.Zero).ToUnixTimeSeconds();
		}

		public static DateTime FromUnixSeconds(long seconds)
		{
			if (seconds == 0)
			{
				return DateTime.MinValue;
			}
			return DateTimeOffset.FromUnixTimeSeconds(seconds).UtcDateTime;
		}

		public static string MakeSafeFileName(string key)
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

		public static string DetectImageContentType(byte[] data)
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

		public static PulseAlbum ReadAlbumRow(SqliteDataReader reader)
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

		public static PulseTrack ReadSongRow(SqliteDataReader reader)
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

		public static PulsePlaylist ReadPlaylistRow(SqliteDataReader reader)
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
	}
}

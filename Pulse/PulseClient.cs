using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.Maui.ApplicationModel;
using Thump.Data;
using Thump.Utility;

namespace Thump.Pulse
{
	public class PulseSearchData
	{
		public List<PulseArtist> Artists;
		public List<PulseAlbum> Albums;
		public List<PulseTrack> Songs;

		public PulseSearchData()
		{
			Artists = new List<PulseArtist>();
			Albums = new List<PulseAlbum>();
			Songs = new List<PulseTrack>();
		}
	}

	public class PulseObject : ThumpDataOb
	{
		public string Id { get; set; }
	}
	public class PulseTrack : PulseObject
	{
		public string Title { get; set; }
		public string Artist { get; set; }
		public string ArtistId { get; set; }
		public string Album { get; set; }
		public string AlbumId { get; set; }
		public string CoverArt { private get; set; }
		public int Duration { get; set; }

		public PulseTrack()
		{
			Kind = eDataType.Track;
		}

		public string ImageID
		{
			get
			{
				if (!string.IsNullOrEmpty(CoverArt))
				{
					return CoverArt;
				}
				if (!string.IsNullOrEmpty(AlbumId))
				{
					return AlbumId;
				}
				return null;
			}
		}
	}

	public class PulseAlbum : PulseObject
	{
		public string Name { get; set; }
		public string Artist { get; set; }
		public string ArtistId { get; set; }
		public string CoverArt { get; set; }
		public int Year { get; set; }
		public int SongCount { get; set; }
		public int Duration { get; set; }
		public List<PulseTrack> Songs { get; set; }

		public PulseAlbum()
		{
			Songs = new List<PulseTrack>();
			Kind = eDataType.Album;
		}
	}

	public class PulsePlaylist : PulseObject
	{
		public string Name { get; set; }
		public string CoverArt { get; set; }
		public int SongCount { get; set; }
		public int Duration { get; set; }
		public float Score { get; set; }
		public DateTime LastPlayed { get; set; }
		public List<PulseTrack> Songs { get; set; }

		public PulsePlaylist()
		{
			Songs = new List<PulseTrack>();
			Kind = eDataType.Playlist;
		}
	}

	public class PulseArtist : PulseObject
	{
		public string Name { get; set; }
		public string CoverArt { get; set; }
		public int AlbumCount { get; set; }
		public int PlayCount { get; set; }
		public float Score { get; set; }
		public DateTime LastPlayed { get; set; }

		public PulseArtist()
		{
			Kind = eDataType.Artist;
		}
	}

	public class PulseGenre : PulseObject
	{
		public string Name { get; set; }
		public int SongCount { get; set; }
		public int AlbumCount { get; set; }
		public PulseGenre()
		{
			Kind = eDataType.Genre;
		}
	}

	public class PulseClient
	{
		public enum eSubSonicAuthType
		{
			Token,
			Legacy
		}
		
		private HttpClient m_httpClient;
		private string m_baseUrl;
		private string m_user;
		private string m_apiParams;
		private eSubSonicAuthType m_authType;

		private bool m_bIsOnline = false;
		/// <summary>
		/// todo this seems dumb now that we have a real cache
		/// </summary>
		private ConcurrentDictionary<string, byte[]> m_imageCache = new ConcurrentDictionary<string, byte[]>();

		public PulseClient()
		{
		}

		public void SetServerParams(string ip, string port, string username, string password, eSubSonicAuthType authType, bool enableSSL)
		{
			//todo validate these strings

			string prefix = "http://";
			if (enableSSL)
				prefix = "https://";

			m_baseUrl = prefix + ip + ":" + port;
			m_user = username;
			m_apiParams = "u=" + m_user + "&p=enc:"+ password + "&v=1.13.0&c=PulseMaui&f=json";
			m_authType = authType;

			if (m_httpClient != null)
				m_httpClient.Dispose();

			HttpClientHandler handler = new HttpClientHandler();
			handler.ServerCertificateCustomValidationCallback = AcceptAnyServerCertificate;
			m_httpClient = new HttpClient(handler);
			m_httpClient.Timeout = TimeSpan.FromSeconds(10);
			TestConnection(out JsonElement discard);
		}

		public bool TestConnection(out JsonElement response)
		{
			try
			{
				if (SubsonicGet("ping", out response))
				{
					m_bIsOnline = true;
					return true;
				}
			}
			catch (Exception ex)
			{
				Log.Exception(ex);
			}
			m_bIsOnline = false;
			response = default;
			return false;
		}
		private static bool AcceptAnyServerCertificate(HttpRequestMessage request, System.Security.Cryptography.X509Certificates.X509Certificate2 certificate, System.Security.Cryptography.X509Certificates.X509Chain chain, System.Net.Security.SslPolicyErrors errors)
		{

			return true;
		}

		public bool IsOnline()
		{
			return m_bIsOnline;
		}
		public string BuildStreamUrl(string trackId)
		{
			return BuildRestUrl("stream", "id=" + Uri.EscapeDataString(trackId));
		}

		public string BuildRestUrl(string endpoint, string extraParams = null)
		{
			string url = m_baseUrl + "/rest/" + endpoint + "?" + m_apiParams;
			if (!string.IsNullOrEmpty(extraParams))
			{
				url = url + "&" + extraParams;
			}
			return url;
		}

		public void GetArtists(Action<List<PulseArtist>> onComplete)
		{
			Task.Run(() =>
			{
				List<PulseArtist> results = new List<PulseArtist>();
				try
				{
					if (SubsonicGet("getArtists", out JsonElement response))
					{
						if (response.TryGetProperty("artists", out JsonElement artists) &&
							artists.TryGetProperty("index", out JsonElement indexes) &&
							indexes.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement index in indexes.EnumerateArray())
							{
								if (index.TryGetProperty("artist", out JsonElement artistArray) &&
									artistArray.ValueKind == JsonValueKind.Array)
								{
									foreach (JsonElement artistElement in artistArray.EnumerateArray())
									{
										PulseArtist artist = new PulseArtist();
										artist.Id = JsonHelper.GetString(artistElement, "id");
										artist.Name = JsonHelper.GetString(artistElement, "name");
										artist.CoverArt = JsonHelper.GetString(artistElement, "coverArt");
										artist.AlbumCount = JsonHelper.GetInt(artistElement, "albumCount");
										results.Add(artist);
									}
								}
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				MainThread.BeginInvokeOnMainThread(() => { onComplete(results); });
			});
		}

		public void Search(string query, Action<PulseSearchData> onComplete)
		{
			Task.Run(() =>
			{
				PulseSearchData result = new PulseSearchData();
				try
				{
					string param = "query=" + Uri.EscapeDataString(query)
						+ "&artistCount=10"
						+ "&albumCount=20"
						+ "&songCount=30";

					if (SubsonicGet("search3", out JsonElement response, param))
					{
						if (response.TryGetProperty("searchResult3", out JsonElement searchResult))
						{
							if (searchResult.TryGetProperty("artist", out JsonElement artistArray) && artistArray.ValueKind == JsonValueKind.Array)
							{
								foreach (JsonElement element in artistArray.EnumerateArray())
								{
									PulseArtist artist = new PulseArtist();
									artist.Id = JsonHelper.GetString(element, "id");
									artist.Name = JsonHelper.GetString(element, "name");
									artist.CoverArt = JsonHelper.GetString(element, "coverArt");
									artist.AlbumCount = JsonHelper.GetInt(element, "albumCount");
									result.Artists.Add(artist);
								}
							}
							if (searchResult.TryGetProperty("album", out JsonElement albumArray) && albumArray.ValueKind == JsonValueKind.Array)
							{
								foreach (JsonElement element in albumArray.EnumerateArray())
								{
									result.Albums.Add(ParseAlbum(element));
								}
							}
							if (searchResult.TryGetProperty("song", out JsonElement songArray) && songArray.ValueKind == JsonValueKind.Array)
							{
								foreach (JsonElement element in songArray.EnumerateArray())
								{
									result.Songs.Add(ParseSong(element));
								}
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				PulseSearchData captured = result;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetArtistAlbums(string artistId, Action<List<PulseAlbum>> onComplete)
		{
			Task.Run(() =>
			{
				List<PulseAlbum> results = new List<PulseAlbum>();
				try
				{
					if (SubsonicGet("getArtist", out JsonElement response, "id=" + Uri.EscapeDataString(artistId)))
					{
						if (response.TryGetProperty("artist", out JsonElement artistElement) && artistElement.TryGetProperty("album", out JsonElement albumArray) && albumArray.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement element in albumArray.EnumerateArray())
							{
								results.Add(ParseAlbum(element));
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				List<PulseAlbum> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetAlbum(string albumId, Action<PulseAlbum> onComplete)
		{
			Task.Run(() =>
			{
				PulseAlbum result = new PulseAlbum();
				try
				{
					if (SubsonicGet("getAlbum", out JsonElement response, "id=" + Uri.EscapeDataString(albumId)))
					{
						if (response.TryGetProperty("album", out JsonElement albumElement))
						{
							PulseAlbum album = ParseAlbum(albumElement);
							album.Songs = ParseSongArray(albumElement, "song");
							result = album;
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				PulseAlbum captured = result;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		private PulseAlbum ParseAlbum(JsonElement element)
		{
			PulseAlbum album = new PulseAlbum();
			album.Id = JsonHelper.GetString(element, "id");
			album.Name = JsonHelper.GetString(element, "name");
			album.Artist = JsonHelper.GetString(element, "artist");
			album.ArtistId = JsonHelper.GetString(element, "artistId");
			album.CoverArt = JsonHelper.GetString(element, "coverArt");
			album.Year = JsonHelper.GetInt(element, "year");
			album.SongCount = JsonHelper.GetInt(element, "songCount");
			album.Duration = JsonHelper.GetInt(element, "duration");
			return album;
		}

		public void GetAlbums(Action<List<PulseAlbum>> onComplete)
		{
			Task.Run(() =>
			{
				List<PulseAlbum> results = new List<PulseAlbum>();
				try
				{
					for (int page = 0; page < 200; page++)
					{
						int offset = page * 500;
						if (!SubsonicGet("getAlbumList2", out JsonElement response, "type=alphabeticalByName&size=500&offset=" + offset))
						{
							break;
						}
						int pageCount = 0;
						if (response.TryGetProperty("albumList2", out JsonElement albumList) &&
							albumList.TryGetProperty("album", out JsonElement albumArray) &&
							albumArray.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement element in albumArray.EnumerateArray())
							{
								results.Add(ParseAlbum(element));
								pageCount++;
							}
						}
						if (pageCount < 500)
						{
							break;
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				List<PulseAlbum> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void CreatePlaylist(string name, Action<PulsePlaylist> onComplete)
		{
			Task.Run(() =>
			{
				PulsePlaylist created = null;
				try
				{
					string param = "name=" + Uri.EscapeDataString(name);
					if (SubsonicGet("createPlaylist", out JsonElement response, param))
					{
						if (response.TryGetProperty("playlist", out JsonElement playlistElement))
						{
							created = ParsePlaylist(playlistElement);
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				PulsePlaylist captured = created;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void RenamePlaylist(string playlistId, string newName, Action<bool> onComplete)
		{
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					string param = "playlistId=" + Uri.EscapeDataString(playlistId)
						+ "&name=" + Uri.EscapeDataString(newName);
					ok = SubsonicGet("updatePlaylist", out JsonElement response, param);
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void DeletePlaylist(string playlistId, Action<bool> onComplete)
		{
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					string param = "id=" + Uri.EscapeDataString(playlistId);
					ok = SubsonicGet("deletePlaylist", out JsonElement response, param);
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void AddTrackToPlaylist(string playlistId, string songId, Action<bool> onComplete)
		{
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					string param = "playlistId=" + Uri.EscapeDataString(playlistId)
						+ "&songIdToAdd=" + Uri.EscapeDataString(songId);
					ok = SubsonicGet("updatePlaylist", out JsonElement response, param);
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void RemoveTrackFromPlaylist(string playlistId, int songIndex, Action<bool> onComplete)
		{
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					string param = "playlistId=" + Uri.EscapeDataString(playlistId)
						+ "&songIndexToRemove=" + songIndex;
					ok = SubsonicGet("updatePlaylist", out JsonElement response, param);
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void ReorderPlaylist(string playlistId, int fromIndex, int toIndex, List<PulseTrack> newOrder, Action<bool> onComplete)
		{
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					int divergence = fromIndex;
					if (toIndex < divergence)
					{
						divergence = toIndex;
					}
					System.Text.StringBuilder param = new System.Text.StringBuilder();
					param.Append("playlistId=").Append(Uri.EscapeDataString(playlistId));
					for (int idx = newOrder.Count - 1; idx >= divergence; idx--)
					{
						param.Append("&songIndexToRemove=").Append(idx);
					}
					for (int idx = divergence; idx < newOrder.Count; idx++)
					{
						param.Append("&songIdToAdd=").Append(Uri.EscapeDataString(newOrder[idx].Id));
					}
					ok = SubsonicGet("updatePlaylist", out JsonElement response, param.ToString());
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void MarkPlaylistPlayed(string playlistId, Action<bool> onComplete)
		{
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					string url = m_baseUrl + "/pulse/markPlaylistPlayed?id=" + Uri.EscapeDataString(playlistId) + "&u=" + m_user;
					string json = HttpGet(url);
					ok = json != null;
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void GetPlaylists(Action<List<PulsePlaylist>> onComplete)
		{
			Task.Run(() =>
			{
				List<PulsePlaylist> results = new List<PulsePlaylist>();
				try
				{
					if (SubsonicGet("getPlaylists", out JsonElement response))
					{
						if (response.TryGetProperty("playlists", out JsonElement playlists) &&
							playlists.TryGetProperty("playlist", out JsonElement playlistArray) &&
							playlistArray.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement item in playlistArray.EnumerateArray())
							{
								results.Add(ParsePlaylist(item));
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				MainThread.BeginInvokeOnMainThread(() => { onComplete(results); });
			});
		}

		public void GetPlaylist(string playlistId, Action<PulsePlaylist> onComplete)
		{
			Task.Run(() =>
			{
				PulsePlaylist result = new PulsePlaylist();
				try
				{
					if (SubsonicGet("getPlaylist", out JsonElement response, "id=" + Uri.EscapeDataString(playlistId)))
					{
						if (response.TryGetProperty("playlist", out JsonElement playlistElement))
						{
							result = ParsePlaylist(playlistElement);
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				PulsePlaylist captured = result;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		private PulsePlaylist ParsePlaylist(JsonElement element)
		{
			PulsePlaylist playlist = new PulsePlaylist();
			playlist.Id = JsonHelper.GetString(element, "id");
			playlist.Name = JsonHelper.GetString(element, "name");
			playlist.CoverArt = JsonHelper.GetString(element, "coverArt");
			playlist.SongCount = JsonHelper.GetInt(element, "songCount");
			playlist.Duration = JsonHelper.GetInt(element, "duration");
			playlist.Score = JsonHelper.GetFloat(element, "score");
			playlist.LastPlayed = JsonHelper.GetDateTime(element, "lastPlayed");
			playlist.Songs = ParseSongArray(element, "entry");
			return playlist;
		}

		private List<PulseTrack> ParseSongArray(JsonElement parent, string propertyName)
		{
			List<PulseTrack> songs = new List<PulseTrack>();
			if (parent.TryGetProperty(propertyName, out JsonElement array) &&
				array.ValueKind == JsonValueKind.Array)
			{
				foreach (JsonElement element in array.EnumerateArray())
				{
					songs.Add(ParseSong(element));
				}
			}
			return songs;
		}

		private PulseTrack ParseSong(JsonElement element)
		{
			PulseTrack song = new PulseTrack();
			song.Id = JsonHelper.GetString(element, "id");
			song.Title = JsonHelper.GetString(element, "title");
			song.Artist = JsonHelper.GetString(element, "artist");
			song.ArtistId = JsonHelper.GetString(element, "artistId");
			song.Album = JsonHelper.GetString(element, "album");
			song.AlbumId = JsonHelper.GetString(element, "albumId");
			song.CoverArt = JsonHelper.GetString(element, "coverArt");
			song.Duration = JsonHelper.GetInt(element, "duration");
			return song;
		}

		public void GetCoverArt(string coverArtId, Action<byte[]> onComplete)
		{
			if (string.IsNullOrEmpty(coverArtId))
			{
				onComplete(null);
				return;
			}

			int size = 512;//?? Where the fuck did this come from, I sure as fuck don't support it
			string url = BuildCoverArtUrl(coverArtId, size);
			if (m_imageCache.TryGetValue(url, out byte[] cached))
			{
				onComplete(cached);
				return;
			}
			Task.Run(() =>
			{
				try
				{
					HttpResponseMessage response = m_httpClient.SendAsync(new HttpRequestMessage(HttpMethod.Get, url)).Result;
					byte[] data = response.Content.ReadAsByteArrayAsync().Result;
					m_imageCache[url] = data;
					MainThread.BeginInvokeOnMainThread(() => { onComplete(data); });
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
					MainThread.BeginInvokeOnMainThread(() => { onComplete(null); });
				}
			});
		}

		public void GetTrackAudio(string trackId, Action<byte[]> onComplete)
		{
			if (string.IsNullOrEmpty(trackId))
			{
				onComplete(null);
				return;
			}
			string url = BuildStreamUrl(trackId);
			Task.Run(() =>
			{
				try
				{
					HttpResponseMessage response = m_httpClient.SendAsync(new HttpRequestMessage(HttpMethod.Get, url)).Result;
					if (!response.IsSuccessStatusCode)
					{
						Log.Error("Audio fetch failed: " + url + " status: " + response.StatusCode);
						MainThread.BeginInvokeOnMainThread(() => { onComplete(null); });
						return;
					}
					byte[] data = response.Content.ReadAsByteArrayAsync().Result;
					MainThread.BeginInvokeOnMainThread(() => { onComplete(data); });
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
					MainThread.BeginInvokeOnMainThread(() => { onComplete(null); });
				}
			});
		}

		private bool SubsonicGet(string endpoint, out JsonElement jsonElement, string extraParams = null)
		{
			bool retVal = false;
			jsonElement = default;
			string url = BuildRestUrl(endpoint, extraParams);
			HttpResponseMessage httpResponse = m_httpClient.SendAsync(new HttpRequestMessage(HttpMethod.Get, url)).Result;
			string json = httpResponse.Content.ReadAsStringAsync().Result;
			JsonDocument doc = JsonDocument.Parse(json);
			JsonElement root = doc.RootElement;

			if (root.TryGetProperty("subsonic-response", out JsonElement response))
			{
				string status = JsonHelper.GetString(response, "status");
				if (status == "ok")
				{
					jsonElement = response;
					retVal = true;
				}
			}
			if (!retVal)
			{
				Log.Error("Invalid subsonic response: " + json);
			}
			return retVal;
		}

		private string BuildCoverArtUrl(string coverArtId, int size = 0)
		{
			if (string.IsNullOrEmpty(coverArtId))
			{
				return null;
			}
			string sizeParam = size > 0 ? "&size=" + size : "";
			return BuildRestUrl("getCoverArt", "id=" + Uri.EscapeDataString(coverArtId) + sizeParam);
		}

		public void GetRecentlyPlayed(Action<List<PulseObject>> onComplete)
		{

			Task.Run(() =>
			{
				List<PulseObject> results = new List<PulseObject>();
				try
				{
					int count = 50;
					string url = m_baseUrl + "/pulse/recentlyPlayed?count=" + count + "&u=" + m_user;
					string json = HttpGet(url);
					if (json != null)
					{
						JsonDocument doc = JsonDocument.Parse(json);
						if (doc.RootElement.TryGetProperty("tracks", out JsonElement tracks) && tracks.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement element in tracks.EnumerateArray())
							{
								// this is going to fail, recently played includes songs, playlists, artists, etc..
								results.Add(ParseSong(element));
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				MainThread.BeginInvokeOnMainThread(() => { onComplete(results); });
			});
		}

		public void GetPopularArtists(Action<List<PulseArtist>> onComplete)
		{
			Task.Run(() =>
			{
				List<PulseArtist> results = new List<PulseArtist>();
				try
				{
					int count = 50;
					string url = m_baseUrl + "/pulse/popularArtists?count=" + count + "&u=" + m_user;
					string json = HttpGet(url);
					if (json != null)
					{
						JsonDocument doc = JsonDocument.Parse(json);
						if (doc.RootElement.TryGetProperty("artists", out JsonElement artists) && artists.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement element in artists.EnumerateArray())
							{
								PulseArtist artist = new PulseArtist();
								artist.Id = JsonHelper.GetString(element, "id");
								artist.Name = JsonHelper.GetString(element, "name");
								artist.CoverArt = JsonHelper.GetString(element, "coverArt");
								artist.AlbumCount = JsonHelper.GetInt(element, "albumCount");
								artist.PlayCount = JsonHelper.GetInt(element, "playCount");
								artist.Score = JsonHelper.GetFloat(element, "score");
								artist.LastPlayed = JsonHelper.GetDateTime(element, "lastPlayed");
								results.Add(artist);
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				MainThread.BeginInvokeOnMainThread(() => { onComplete(results); });
			});
		}

		public void GetTopPlaylists(Action<List<PulsePlaylist>> onComplete)
		{
			GetRankedPlaylists("topPlaylists", 50, onComplete);
		}

		public void GetRecentPlaylists(Action<List<PulsePlaylist>> onComplete)
		{
			GetRankedPlaylists("recentPlaylists", 50, onComplete);
		}

	
		public void GetRecentlyAdded(Action<List<PulseObject>> onComplete)
		{
			Task.Run(() =>
			{
				List<PulseObject> results = new List<PulseObject>();
				try
				{
					if (SubsonicGet("getAlbumList2", out JsonElement response, "type=newest&size=50"))
					{
						if (response.TryGetProperty("albumList2", out JsonElement albumList) &&
							albumList.TryGetProperty("album", out JsonElement albumArray) &&
							albumArray.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement element in albumArray.EnumerateArray())
							{
								results.Add(ParseAlbum(element));
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				List<PulseObject> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetGenres(Action<List<PulseGenre>> onComplete)
		{
			Task.Run(() =>
			{
				List<PulseGenre> results = new List<PulseGenre>();
				try
				{
					if (SubsonicGet("getGenres", out JsonElement response))
					{
						if (response.TryGetProperty("genres", out JsonElement genres) &&
							genres.TryGetProperty("genre", out JsonElement genreArray) &&
							genreArray.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement element in genreArray.EnumerateArray())
							{
								string value = JsonHelper.GetString(element, "value");
								PulseGenre genre = new PulseGenre();
								genre.Id = value;
								genre.Name = value;
								genre.SongCount = JsonHelper.GetInt(element, "songCount");
								genre.AlbumCount = JsonHelper.GetInt(element, "albumCount");
								results.Add(genre);
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				List<PulseGenre> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetTopItems(Action<List<PulseObject>> onComplete)
		{
			Task.Run(() =>
			{
				List<PulseObject> results = new List<PulseObject>();
				try
				{
					int count = 50;
					string url = m_baseUrl + "/pulse/topPlaylists?count=" + count + "&u=" + m_user;
					string json = HttpGet(url);
					if (json != null)
					{
						JsonDocument doc = JsonDocument.Parse(json);
						if (doc.RootElement.TryGetProperty("playlists", out JsonElement playlists) && playlists.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement element in playlists.EnumerateArray())
							{
								PulsePlaylist playlist = ParsePlaylist(element);
								results.Add(playlist);
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				List<PulseObject> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetTracksForGenre(string genre, Action<List<PulseTrack>> onComplete)
		{
			MainThread.BeginInvokeOnMainThread(() => { onComplete(null); });
		}

		public void GetFavorites(Action<List<PulseTrack>> onComplete)
		{
			MainThread.BeginInvokeOnMainThread(() => { onComplete(null); });
		}

		private void GetRankedPlaylists(string endpoint, int count, Action<List<PulsePlaylist>> onComplete)
		{
			Task.Run(() =>
			{
				List<PulsePlaylist> results = new List<PulsePlaylist>();
				try
				{
					string url = m_baseUrl + "/pulse/" + endpoint + "?count=" + count + "&u=" + m_user;
					string json = HttpGet(url);
					if (json != null)
					{
						JsonDocument doc = JsonDocument.Parse(json);
						if (doc.RootElement.TryGetProperty("playlists", out JsonElement playlists) && playlists.ValueKind == JsonValueKind.Array)
						{
							foreach (JsonElement element in playlists.EnumerateArray())
							{
								
								PulsePlaylist playlist = ParsePlaylist(element);
								results.Add(playlist);
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					System.Diagnostics.Debugger.Break();
				}
				MainThread.BeginInvokeOnMainThread(() => { onComplete(results); });
			});
		}

		private string HttpGet(string url)
		{
			HttpResponseMessage response = m_httpClient.SendAsync(new HttpRequestMessage(HttpMethod.Get, url)).Result;
			if (!response.IsSuccessStatusCode)
			{
				Log.Error("HTTP request failed: " + url + " status: " + response.StatusCode);
				return null;
			}
			return response.Content.ReadAsStringAsync().Result;
		}


	}
}

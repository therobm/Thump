using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
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
		public List<PulsePlaylist> Playlists;

		public PulseSearchData()
		{
			Artists = new List<PulseArtist>();
			Albums = new List<PulseAlbum>();
			Songs = new List<PulseTrack>();
			Playlists = new List<PulsePlaylist>();
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
		public bool Starred { get; set; }

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

	public class PulsePodcastEpisode : PulseObject
	{
		public string Title { get; set; }
		public string Description { get; set; }
		public string StreamId { get; set; }
		public string CoverArt { get; set; }
		public string PublishDate { get; set; }
		public string Status { get; set; }
		public int Duration { get; set; }

		public PulsePodcastEpisode()
		{
			Kind = eDataType.PodcastEpisode;
		}
	}

	public class PulsePodcastChannel : PulseObject
	{
		public string Title { get; set; }
		public string Description { get; set; }
		public string CoverArt { get; set; }
		public string Url { get; set; }
		public string Status { get; set; }
		public List<PulsePodcastEpisode> Episodes { get; set; }

		public PulsePodcastChannel()
		{
			Episodes = new List<PulsePodcastEpisode>();
			Kind = eDataType.Podcast;
		}
	}

	public class SubsonicAPI : IMediaClient
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
		private Thread m_thread;
		private bool m_bInitialized = false;
		private bool m_bIsOnline = false;
		/// <summary>
		/// todo this seems dumb now that we have a real cache
		/// </summary>
		private ConcurrentDictionary<string, byte[]> m_imageCache = new ConcurrentDictionary<string, byte[]>();

		private object m_httpClientLock = new object();

		public SubsonicAPI()
		{
			m_thread = new Thread(ConnectionLoop);
			m_thread.IsBackground = true;
			m_thread.Start();
		}

		private void ConnectionLoop()
		{
			while (true)
			{
				if (m_bInitialized)
				{
					Ping(out JsonElement response);
				}
				Thread.Sleep(5000);
			}
		}

		public void SetServerParams(string ip, string port, string username, string password, eSubSonicAuthType authType, bool enableSSL)
		{
			// Accept an IP/host that may have been entered (or stored) with a
			// scheme and/or trailing slash; strip them so the prefix derived from
			// enableSSL is authoritative. Otherwise a value like "https://host"
			// produced "http://https://host:port".
			ip = ip.Trim().Replace("http://", "").Replace("https://", "").TrimEnd('/');

			string prefix = "http://";
			if (enableSSL)
				prefix = "https://";

			m_baseUrl = prefix + ip + ":" + port;
			m_user = username;
			m_apiParams = "u=" + Uri.EscapeDataString(m_user) + "&p=enc:" + Uri.EscapeDataString(password) + "&v=1.13.0&c=PulseMaui&f=json";
			m_authType = authType;

			if (m_httpClient != null)
				m_httpClient.Dispose();

			HttpClientHandler handler = new HttpClientHandler();
			handler.ServerCertificateCustomValidationCallback = AcceptAnyServerCertificate;

			HttpClient oldClient;
			lock(m_httpClientLock)
			{
				oldClient = m_httpClient;
				m_httpClient = new HttpClient(handler);
				m_httpClient.Timeout = TimeSpan.FromSeconds(10);
			}
			if (oldClient != null)
			{
				oldClient.Dispose();
			}

			m_bInitialized = true;
			Ping(out JsonElement discard);
		}

		public bool TestConnection(out JsonElement response)
		{
			return Ping(out response);
		}
		private bool Ping(out JsonElement response)
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
				//Don't log ping failures, this is our online/offline state polling
				//Log.Exception(ex);
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
			if (!IsOnline())
			{
				onComplete(new List<PulseArtist>());
				return;
			}
			Task.Run(() =>
			{
				List<PulseArtist> results = new List<PulseArtist>();
				try
				{
					if (SubsonicGet("getArtists", out JsonElement response))
					{
						JsonElement artists;
						JsonElement indexes = default;
						bool validParams = true;
						if (!response.TryGetProperty("artists", out artists))
							validParams = false;
						if (validParams)
						{
							if (!artists.TryGetProperty("index", out indexes))
								validParams = false;
						}
						if (indexes.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
						{
							foreach (JsonElement index in indexes.EnumerateArray())
							{
								JsonElement artistArray;
								bool validArtist = true;
								if (!index.TryGetProperty("artist", out artistArray))
									validArtist = false;
								if (artistArray.ValueKind != JsonValueKind.Array)
									validArtist = false;
								if (validArtist)
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

				}
				MainThread.BeginInvokeOnMainThread(() => { onComplete(results); });
			});
		}

		public void GetPodcasts(Action<List<PulsePodcastChannel>> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new List<PulsePodcastChannel>());
				return;
			}
			Task.Run(() =>
			{
				List<PulsePodcastChannel> results = new List<PulsePodcastChannel>();
				try
				{
					if (SubsonicGet("getPodcasts", out JsonElement response, "includeEpisodes=true"))
					{
						JsonElement podcasts;
						JsonElement channelArray = default;
						bool validParams = true;
						if (!response.TryGetProperty("podcasts", out podcasts))
							validParams = false;
						if (validParams)
						{
							if (!podcasts.TryGetProperty("channel", out channelArray))
								validParams = false;
						}
						if (channelArray.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
						{
							foreach (JsonElement element in channelArray.EnumerateArray())
							{
								results.Add(ParsePodcastChannel(element));
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);

				}
				List<PulsePodcastChannel> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		private PulsePodcastChannel ParsePodcastChannel(JsonElement element)
		{
			PulsePodcastChannel channel = new PulsePodcastChannel();
			channel.Id = JsonHelper.GetString(element, "id");
			channel.Title = JsonHelper.GetString(element, "title");
			channel.Description = JsonHelper.GetString(element, "description");
			channel.CoverArt = JsonHelper.GetString(element, "coverArt");
			channel.Url = JsonHelper.GetString(element, "url");
			channel.Status = JsonHelper.GetString(element, "status");
			JsonElement episodeArray;
			bool validEpisodes = true;
			if (!element.TryGetProperty("episode", out episodeArray))
				validEpisodes = false;
			if (episodeArray.ValueKind != JsonValueKind.Array)
				validEpisodes = false;
			if (validEpisodes)
			{
				foreach (JsonElement episodeElement in episodeArray.EnumerateArray())
				{
					channel.Episodes.Add(ParsePodcastEpisode(episodeElement));
				}
			}
			return channel;
		}

		private PulsePodcastEpisode ParsePodcastEpisode(JsonElement element)
		{
			PulsePodcastEpisode episode = new PulsePodcastEpisode();
			episode.Id = JsonHelper.GetString(element, "id");
			episode.StreamId = JsonHelper.GetString(element, "streamId");
			episode.Title = JsonHelper.GetString(element, "title");
			episode.Description = JsonHelper.GetString(element, "description");
			episode.CoverArt = JsonHelper.GetString(element, "coverArt");
			episode.PublishDate = JsonHelper.GetString(element, "publishDate");
			episode.Status = JsonHelper.GetString(element, "status");
			episode.Duration = JsonHelper.GetInt(element, "duration");
			return episode;
		}

		public void Search(string query, Action<PulseSearchData> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new PulseSearchData());
				return;
			}
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

					if (SubsonicGet("getPlaylists", out JsonElement playlistResponse))
					{
						bool validParams = true;

						JsonElement playlists = default;
						if (!playlistResponse.TryGetProperty("playlists", out playlists))
							validParams = false;

						JsonElement playlistArray = default;
						if (validParams && !playlists.TryGetProperty("playlist", out playlistArray))
							validParams = false;						
											
						if (validParams && playlistArray.ValueKind != JsonValueKind.Array)
							validParams = false;

						if (validParams)
						{
							string lowerQuery = query.ToLowerInvariant();
							foreach (JsonElement element in playlistArray.EnumerateArray())
							{
								PulsePlaylist playlist = ParsePlaylist(element);
								if (playlist.Name != null && playlist.Name.ToLowerInvariant().Contains(lowerQuery))
								{
									result.Playlists.Add(playlist);
								}
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
				}
				PulseSearchData captured = result;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetArtistAlbums(string artistId, Action<List<PulseAlbum>> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new List<PulseAlbum>());
				return;
			}
			Task.Run(() =>
			{
				List<PulseAlbum> results = new List<PulseAlbum>();
				try
				{
					if (SubsonicGet("getArtist", out JsonElement response, "id=" + Uri.EscapeDataString(artistId)))
					{
						JsonElement artistElement;
						JsonElement albumArray = default;
						bool validParams = true;
						if (!response.TryGetProperty("artist", out artistElement))
							validParams = false;
						if (validParams)
						{
							if (!artistElement.TryGetProperty("album", out albumArray))
								validParams = false;
						}
						if (albumArray.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
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

				}
				List<PulseAlbum> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetAlbum(string albumId, Action<PulseAlbum> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new PulseAlbum());
				return;
			}
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
			if (!IsOnline())
			{
				onComplete(new List<PulseAlbum>());
				return;
			}
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
						JsonElement albumList;
						JsonElement albumArray = default;
						bool validParams = true;
						if (!response.TryGetProperty("albumList2", out albumList))
							validParams = false;
						if (validParams)
						{
							if (!albumList.TryGetProperty("album", out albumArray))
								validParams = false;
						}
						if (albumArray.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
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

				}
				List<PulseAlbum> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void CreatePlaylist(string name, Action<PulsePlaylist> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(null);
				return;
			}
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

				}
				PulsePlaylist captured = created;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void RenamePlaylist(string playlistId, string newName, Action<bool> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(false);
				return;
			}
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

				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void Star(string trackId, Action<bool> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(false);
				return;
			}
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					string param = "id=" + Uri.EscapeDataString(trackId);
					ok = SubsonicGet("star", out JsonElement response, param);
				}
				catch (Exception ex)
				{
					Log.Exception(ex);

				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void Unstar(string trackId, Action<bool> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(false);
				return;
			}
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					string param = "id=" + Uri.EscapeDataString(trackId);
					ok = SubsonicGet("unstar", out JsonElement response, param);
				}
				catch (Exception ex)
				{
					Log.Exception(ex);

				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void DeletePlaylist(string playlistId, Action<bool> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(false);
				return;
			}
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

				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void AddTrackToPlaylist(string playlistId, string songId, Action<bool> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(false);
				return;
			}
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					string param = "playlistId=" + Uri.EscapeDataString(playlistId) + "&songIdToAdd=" + Uri.EscapeDataString(songId);
					ok = SubsonicGet("updatePlaylist", out JsonElement response, param);
				}
				catch (Exception ex)
				{
					Log.Exception(ex);

				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void RemoveTrackFromPlaylist(string playlistId, int songIndex, Action<bool> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(false);
				return;
			}
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

				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void ReorderPlaylist(string playlistId, int fromIndex, int toIndex, List<PulseTrack> newOrder, Action<bool> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(false);
				return;
			}
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
					StringBuilder param = new StringBuilder();
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

				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void MarkPlaylistPlayed(string playlistId, Action<bool> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(false);
				return;
			}
			Task.Run(() =>
			{
				bool ok = false;
				try
				{
					string url = m_baseUrl + "/pulse/markPlaylistPlayed?id=" + Uri.EscapeDataString(playlistId) + "&u=" + Uri.EscapeDataString(m_user);
					string json = HttpGet(url);
					ok = json != null;
				}
				catch (Exception ex)
				{
					Log.Exception(ex);

				}
				bool result = ok;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(result); });
			});
		}

		public void GetPlaylists(Action<List<PulsePlaylist>> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new List<PulsePlaylist>());
				return;
			}
			Task.Run(() =>
			{
				List<PulsePlaylist> results = new List<PulsePlaylist>();
				try
				{
					if (SubsonicGet("getPlaylists", out JsonElement response))
					{
						JsonElement playlists;
						JsonElement playlistArray = default;
						bool validParams = true;
						if (!response.TryGetProperty("playlists", out playlists))
							validParams = false;
						if (validParams)
						{
							if (!playlists.TryGetProperty("playlist", out playlistArray))
								validParams = false;
						}
						if (playlistArray.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
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

				}
				MainThread.BeginInvokeOnMainThread(() => { onComplete(results); });
			});
		}

		public void GetPlaylist(string playlistId, Action<PulsePlaylist> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new PulsePlaylist());
				return;
			}
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

		// Pulse-native playlist envelope: typed PlaylistInfo serialized with
		// PascalCase fields. The synthetic Subsonic-style coverArt id is
		// derived locally; the per-user LastPlayed (when present) wins over
		// the aggregate.
		private PulsePlaylist ParsePulsePlaylist(JsonElement element)
		{
			PulsePlaylist playlist = new PulsePlaylist();
			playlist.Id = JsonHelper.GetString(element, "Id");
			playlist.Name = JsonHelper.GetString(element, "Name");
			playlist.CoverArt = "pl-" + playlist.Id;
			int songCount = 0;
			JsonElement trackIds = default;
			bool hasTrackIds = element.TryGetProperty("TrackIds", out trackIds);
			if (hasTrackIds && trackIds.ValueKind == JsonValueKind.Array)
			{
				songCount = trackIds.GetArrayLength();
			}
			playlist.SongCount = songCount;
			playlist.Duration = JsonHelper.GetInt(element, "DurationSeconds");
			playlist.Score = 0f;
			DateTime lastPlayed = DateTime.MinValue;
			JsonElement userMap = default;
			bool hasUserMap = element.TryGetProperty("UserLastPlayed", out userMap);
			if (hasUserMap && userMap.ValueKind == JsonValueKind.Object && !string.IsNullOrEmpty(m_user))
			{
				JsonElement userValue = default;
				bool hasUserEntry = userMap.TryGetProperty(m_user, out userValue);
				if (hasUserEntry && userValue.ValueKind == JsonValueKind.String)
				{
					DateTime.TryParse(userValue.GetString(), System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.RoundtripKind, out lastPlayed);
				}
			}
			if (lastPlayed == DateTime.MinValue)
			{
				lastPlayed = JsonHelper.GetDateTime(element, "LastPlayed");
			}
			playlist.LastPlayed = lastPlayed;
			return playlist;
		}

		private List<PulseTrack> ParseSongArray(JsonElement parent, string propertyName)
		{
			List<PulseTrack> songs = new List<PulseTrack>();
			JsonElement array;
			bool validParams = true;
			if (!parent.TryGetProperty(propertyName, out array))
				validParams = false;
			if (array.ValueKind != JsonValueKind.Array)
				validParams = false;
			if (validParams)
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
			string starredValue = JsonHelper.GetString(element, "starred");
			song.Starred = !string.IsNullOrEmpty(starredValue);
			return song;
		}

		public void GetCoverArt(string coverArtId, Action<byte[]> onComplete)
		{
			if (string.IsNullOrEmpty(coverArtId))
			{
				onComplete(null);
				return;
			}
			string url = BuildCoverArtUrl(coverArtId);
			if (m_imageCache.TryGetValue(url, out byte[] cached))
			{
				onComplete(cached);
				return;
			}
			if (!IsOnline())
			{
				onComplete(null);
				return;
			}
			Task.Run(() =>
			{
				try
				{
					HttpResponseMessage response = m_httpClient.SendAsync(new HttpRequestMessage(HttpMethod.Get, url)).Result;
					if (!response.IsSuccessStatusCode)
					{
						Log.Error("Cover art fetch failed: " + url + " status: " + response.StatusCode);
						MainThread.BeginInvokeOnMainThread(() => { onComplete(null); });
						return;
					}
					byte[] data = response.Content.ReadAsByteArrayAsync().Result;
					m_imageCache[url] = data;
					MainThread.BeginInvokeOnMainThread(() => { onComplete(data); });
				}
				catch (Exception ex)
				{
					Log.Exception(ex);

					MainThread.BeginInvokeOnMainThread(() => { onComplete(null); });
				}
			});
		}

		public void GetTrackAudio(string trackId, Action<byte[]> onComplete)
		{
			if (!IsOnline() || string.IsNullOrEmpty(trackId))
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
						onComplete(null);
						return;
					}
					byte[] data = response.Content.ReadAsByteArrayAsync().Result;
					onComplete(data);
				}
				catch (Exception ex)
				{
					Log.Exception(ex);
					onComplete(null);
				}
			});
		}

		private bool SubsonicGet(string endpoint, out JsonElement jsonElement, string extraParams = null)
		{
			bool retVal = false;
			jsonElement = default;
			string url = BuildRestUrl(endpoint, extraParams);

			HttpClient client;
			lock (m_httpClientLock)
			{
				client = m_httpClient;
			}
			if (client == null)
			{
				jsonElement = default;
				return false;
			}
			HttpResponseMessage httpResponse = client.SendAsync(new HttpRequestMessage(HttpMethod.Get, url)).Result;
			if (!httpResponse.IsSuccessStatusCode)
			{
				Log.Error("Subsonic request failed: " + url + " status: " + httpResponse.StatusCode);
				jsonElement = default;
				return false;
			}
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

		private string BuildCoverArtUrl(string coverArtId)
		{
			if (string.IsNullOrEmpty(coverArtId))
			{
				return null;
			}

			return BuildRestUrl("getCoverArt", "id=" + Uri.EscapeDataString(coverArtId));
		}

		public void GetRecentlyPlayed(Action<List<PulseObject>> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new List<PulseObject>());
				return;
			}
			Task.Run(() =>
			{
				List<PulseObject> results = new List<PulseObject>();
				try
				{
					int count = 50;
					string url = m_baseUrl + "/pulse/recentlyPlayed?count=" + count + "&u=" + Uri.EscapeDataString(m_user);
					string json = HttpGet(url);
					if (json != null)
					{
						JsonDocument doc = JsonDocument.Parse(json);
						JsonElement tracks;
						bool validParams = true;
						if (!doc.RootElement.TryGetProperty("tracks", out tracks))
							validParams = false;
						if (tracks.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
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

				}
				MainThread.BeginInvokeOnMainThread(() => { onComplete(results); });
			});
		}

		public void GetPopularArtists(Action<List<PulseArtist>> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new List<PulseArtist>());
				return;
			}
			Task.Run(() =>
			{
				List<PulseArtist> results = new List<PulseArtist>();
				try
				{
					int count = 50;
					string url = m_baseUrl + "/pulse/popularArtists?count=" + count + "&u=" + Uri.EscapeDataString(m_user);
					string json = HttpGet(url);
					if (json != null)
					{
						JsonDocument doc = JsonDocument.Parse(json);
						JsonElement artists;
						bool validParams = true;
						if (!doc.RootElement.TryGetProperty("artists", out artists))
							validParams = false;
						if (artists.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
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
			if (!IsOnline())
			{
				onComplete(new List<PulseObject>());
				return;
			}
			Task.Run(() =>
			{
				List<PulseObject> results = new List<PulseObject>();
				try
				{
					if (SubsonicGet("getAlbumList2", out JsonElement response, "type=newest&size=50"))
					{
						JsonElement albumList;
						JsonElement albumArray = default;
						bool validParams = true;
						if (!response.TryGetProperty("albumList2", out albumList))
							validParams = false;
						if (validParams)
						{
							if (!albumList.TryGetProperty("album", out albumArray))
								validParams = false;
						}
						if (albumArray.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
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

				}
				List<PulseObject> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetGenres(Action<List<PulseGenre>> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new List<PulseGenre>());
				return;
			}
			Task.Run(() =>
			{
				List<PulseGenre> results = new List<PulseGenre>();
				try
				{
					if (SubsonicGet("getGenres", out JsonElement response))
					{
						JsonElement genres;
						JsonElement genreArray = default;
						bool validParams = true;
						if (!response.TryGetProperty("genres", out genres))
							validParams = false;
						if (validParams)
						{
							if (!genres.TryGetProperty("genre", out genreArray))
								validParams = false;
						}
						if (genreArray.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
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

				}
				List<PulseGenre> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetTopItems(Action<List<PulseObject>> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new List<PulseObject>());
				return;
			}
			Task.Run(() =>
			{
				List<PulseObject> results = new List<PulseObject>();
				try
				{
					int count = 50;
					string url = m_baseUrl + "/pulse/topPlaylists?count=" + count + "&u=" + Uri.EscapeDataString(m_user);
					string json = HttpGet(url);
					if (json != null)
					{
						JsonDocument doc = JsonDocument.Parse(json);
						JsonElement item = default;
						JsonElement playlists = default;
						bool validParams = true;
						if (!doc.RootElement.TryGetProperty("item", out item))
							validParams = false;
						if (validParams && !item.TryGetProperty("Playlists", out playlists))
							validParams = false;
						if (validParams && playlists.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
						{
							foreach (JsonElement element in playlists.EnumerateArray())
							{
								PulsePlaylist playlist = ParsePulsePlaylist(element);
								results.Add(playlist);
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);

				}
				List<PulseObject> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetTracksForGenre(string genre, Action<List<PulseTrack>> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new List<PulseTrack>());
				return;
			}
			Task.Run(() =>
			{
				List<PulseTrack> results = new List<PulseTrack>();
				try
				{
					string param = "genre=" + Uri.EscapeDataString(genre) + "&count=500&offset=0";
					if (SubsonicGet("getSongsByGenre", out JsonElement response, param))
					{
						JsonElement songsByGenre;
						JsonElement songArray = default;
						bool validParams = true;
						if (!response.TryGetProperty("songsByGenre", out songsByGenre))
							validParams = false;
						if (validParams)
						{
							if (!songsByGenre.TryGetProperty("song", out songArray))
								validParams = false;
						}
						if (songArray.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
						{
							foreach (JsonElement element in songArray.EnumerateArray())
							{
								results.Add(ParseSong(element));
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);

				}
				List<PulseTrack> captured = results;
				MainThread.BeginInvokeOnMainThread(() => { onComplete(captured); });
			});
		}

		public void GetFavorites(Action<List<PulseTrack>> onComplete)
		{
			MainThread.BeginInvokeOnMainThread(() => { onComplete(null); });
		}

		private void GetRankedPlaylists(string endpoint, int count, Action<List<PulsePlaylist>> onComplete)
		{
			if (!IsOnline())
			{
				onComplete(new List<PulsePlaylist>());
				return;
			}
			Task.Run(() =>
			{
				List<PulsePlaylist> results = new List<PulsePlaylist>();
				try
				{
					string url = m_baseUrl + "/pulse/" + endpoint + "?count=" + count + "&u=" + Uri.EscapeDataString(m_user);
					string json = HttpGet(url);
					if (json != null)
					{
						JsonDocument doc = JsonDocument.Parse(json);
						JsonElement item = default;
						JsonElement playlists = default;
						bool validParams = true;
						if (!doc.RootElement.TryGetProperty("item", out item))
							validParams = false;
						if (validParams && !item.TryGetProperty("Playlists", out playlists))
							validParams = false;
						if (validParams && playlists.ValueKind != JsonValueKind.Array)
							validParams = false;
						if (validParams)
						{
							foreach (JsonElement element in playlists.EnumerateArray())
							{
								PulsePlaylist playlist = ParsePulsePlaylist(element);
								results.Add(playlist);
							}
						}
					}
				}
				catch (Exception ex)
				{
					Log.Exception(ex);

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

using System;
using System.Collections.Generic;
using System.Runtime.CompilerServices;
using Microsoft.Maui.ApplicationModel;
using Thump.Pulse;


namespace Thump.Data
{
	public enum eDataType
	{
		Track,
		Album,
		Playlist,
		Artist,
		CoverArt,
		SongData,
		Genre
	}



	public enum eRoutes
	{
		GetArtists,
		GetAlbum,
		GetAlbums,
		GetAlbumsForArtist,
		GetPlaylists,
		GetPlaylist,
		GetGenres,
		GetTracksForGenre,
		GetCoverArt,
		GetRecentlyPlayed,
		GetTopPlaylists,
		GetPopularArtists,
		GetRecentlyAdded,
		GetFavorites,
	}

	public class ThumpDataOb
	{
		public eDataType Kind { get; set; }
	}
	public class CoverArt : ThumpDataOb
	{
		public byte[] m_data;
	}
	public class SongData : ThumpDataOb
	{
		public byte[] m_data;
	}


	public class ThumpData
	{
		public PulseClient Pulse {get {return m_pulseClient; } }
		public ThumpCache Cache {get { return m_cache; } }
		private PulseClient m_pulseClient;
		private ThumpCache m_cache;

		private Dictionary<eRoutes, DataRoute> m_dataRoutes;
		

		public ThumpData(PulseClient pulseClient, ThumpCache cache)
		{
			m_pulseClient = pulseClient;
			m_cache = cache;
			
			m_dataRoutes = new Dictionary<eRoutes, DataRoute>()
			{
				{ eRoutes.GetArtists,				new DataRoute<List<PulseArtist>>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetArtists,			m_cache.GetAllArtists,			m_cache.UpdateAllArtists,			IsValidList<PulseArtist>) },
				{ eRoutes.GetAlbum,					new DataRouteID<PulseAlbum>(this,			eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetAlbum,				m_cache.GetAlbum,				m_cache.UpdateAlbum,				IsValidObject) },
				{ eRoutes.GetAlbums,				new DataRoute<List<PulseAlbum>>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetAlbums,			m_cache.GetAlbums,				m_cache.UpdateAlbums,				IsValidList<PulseAlbum>) },
				{ eRoutes.GetAlbumsForArtist,		new DataRouteID<List<PulseAlbum>>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetArtistAlbums,		m_cache.GetAlbumsForArtist,		m_cache.UpdateAlbumsForArtist,		IsValidList<PulseAlbum>) },
				{ eRoutes.GetPlaylists,				new DataRoute<List<PulsePlaylist>>(this,	eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetPlaylists,			m_cache.GetAllPlaylists,		m_cache.UpdateAllPlaylists,			IsValidList<PulsePlaylist>) },
				{ eRoutes.GetPlaylist,				new DataRouteID<PulsePlaylist>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetPlaylist,			m_cache.GetPlaylist,			m_cache.UpdatePlaylist,				IsValidObject) },
				{ eRoutes.GetGenres,				new DataRoute<List<PulseGenre>>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetGenres,			m_cache.GetGenres,				m_cache.UpdateGenres,				IsValidList<PulseGenre>) },
				{ eRoutes.GetTracksForGenre,		new DataRouteID<List<PulseTrack>>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetTracksForGenre,	m_cache.GetTracksForGenre,		m_cache.UpdateTracksForGenre,		IsValidList<PulseTrack>) },
				{ eRoutes.GetCoverArt,				new DataRouteID<byte[]>(this,				eRouteCachingMethod.LocalFirst,		m_pulseClient.GetCoverArt,			m_cache.GetCoverArt,			m_cache.UpdateCoverArt,				IsValidBinary) },
				{ eRoutes.GetRecentlyPlayed,		new DataRoute<List<PulseObject>>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetRecentlyPlayed,	m_cache.GetRecentlyPlayed,		m_cache.UpdateRecentlyPlayed,		IsValidList<PulseObject>) },
				{ eRoutes.GetTopPlaylists,			new DataRoute<List<PulsePlaylist>>(this,	eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetTopPlaylists,		m_cache.GetTopPlaylists,		m_cache.UpdateTopPlaylists,			IsValidList<PulsePlaylist>) },
				{ eRoutes.GetPopularArtists,		new DataRoute<List<PulseArtist>>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetPopularArtists,	m_cache.GetPopularArtists,		m_cache.UpdatePopularArtists,		IsValidList<PulseArtist>) },
				{ eRoutes.GetRecentlyAdded,			new DataRoute<List<PulseObject>>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetRecentlyAdded,		m_cache.GetRecentlyAdded,		m_cache.UpdateRecentlyAdded,		IsValidList<PulseObject>) },
				{ eRoutes.GetFavorites,				new DataRoute<List<PulseTrack>>(this,		eRouteCachingMethod.NetworkAuthorative,	m_pulseClient.GetFavorites,			m_cache.GetFavorites,			m_cache.UpdateFavorites,			IsValidList<PulseTrack>) },
			};
			
		}

		private void GetData<T>(DataRoute<T> dataRoute, Action<T> callback) where T : class
		{
			dataRoute.GetData(callback);
		}

		private void GetData<T>(DataRouteID<T> dataRoute, string id, Action<T> callback) where T : class
		{
			dataRoute.GetData(id, callback);
		}

		private static bool IsValidList<T>(List<T> list)
		{
			if (list == null)
			{
				return false;
			}
			return list.Count > 0;
		}

		private static bool IsValidObject(PulseObject pulseObject)
		{
			if (pulseObject == null)
			{
				return false;
			}
			return !string.IsNullOrEmpty(pulseObject.Id);
		}

		private static bool IsValidBinary(byte[] data)
		{
			return data != null && data.Length > 0;
		}

		public DataRoute<T> GetDataRoute<T>(eRoutes route) where T : class
		{
			if (m_dataRoutes.TryGetValue(route, out DataRoute dataRoute))
				return dataRoute as DataRoute<T>;
			return null;
		}

		public DataRouteID<T> GetDataRouteID<T>(eRoutes route) where T : class
		{
			if (m_dataRoutes.TryGetValue(route, out DataRoute dataRoute))
				return dataRoute as DataRouteID<T>;
			return null;
		}

		public void GetArtists(Action<List<PulseArtist>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRoute<List<PulseArtist>> dataRoute = GetDataRoute<List<PulseArtist>>(eRoutes.GetArtists);
			if (dataRoute != null)
			{
				GetData(dataRoute, callback);
			}
			else
			{
				callback(new List<PulseArtist>());
			}
		}

		public void GetAlbumsForArtist(PulseArtist artist, Action<List<PulseAlbum>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRouteID<List<PulseAlbum>> dataRoute = GetDataRouteID<List<PulseAlbum>>(eRoutes.GetAlbumsForArtist);
			if (dataRoute != null)
			{
				GetData(dataRoute, artist.Id, callback);
			}
			else
			{
				callback(new List<PulseAlbum>());
			}
		}

		public void GetAlbum(string albumId, Action<PulseAlbum> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRouteID<PulseAlbum> dataRoute = GetDataRouteID<PulseAlbum>(eRoutes.GetAlbum);
			if (dataRoute != null)
			{
				GetData(dataRoute, albumId, callback);
			}
			else
			{
				callback(null);
			}
		}

		public void GetTracksForAlbum(PulseAlbum album, Action<List<PulseTrack>> callback)
		{
			if (callback == null)
			{
				return;
			}
			GetAlbum(album.Id, (fullAlbum) =>
			{
				callback(fullAlbum.Songs);
			});
		}

		public void GetPlaylists(Action<List<PulsePlaylist>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRoute<List<PulsePlaylist>> dataRoute = GetDataRoute<List<PulsePlaylist>>(eRoutes.GetPlaylists);
			if (dataRoute != null)
			{
				GetData(dataRoute, callback);
			}
			else
			{
				callback(new List<PulsePlaylist>());
			}
		}

		public void GetPlaylist(string playlistId, Action<PulsePlaylist> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRouteID<PulsePlaylist> dataRoute = GetDataRouteID<PulsePlaylist>(eRoutes.GetPlaylist);
			if (dataRoute != null)
			{
				GetData(dataRoute, playlistId, callback);
			}
			else
			{
				callback(null);
			}
		}

		public void GetAlbums(Action<List<PulseAlbum>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRoute<List<PulseAlbum>> dataRoute = GetDataRoute<List<PulseAlbum>>(eRoutes.GetAlbums);
			if (dataRoute != null)
			{
				GetData(dataRoute, callback);
			}
			else
			{
				callback(null);
			}
		}

		public void GetGenres(Action<List<PulseGenre>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRoute<List<PulseGenre>> dataRoute = GetDataRoute<List<PulseGenre>>(eRoutes.GetGenres);
			if (dataRoute != null)
			{
				GetData(dataRoute, callback);
			}
			else
			{
				callback(null);
			}
		}

		public void GetTracksForGenre(PulseGenre genre, Action<List<PulseTrack>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRouteID<List<PulseTrack>> dataRoute = GetDataRouteID<List<PulseTrack>>(eRoutes.GetTracksForGenre);
			if (dataRoute != null)
			{
				GetData(dataRoute, genre.Name, callback);
			}
			else
			{
				callback(null);
			}
		}

		public void Search(string query, Action<PulseSearchData> callback)
		{
			if (callback == null)
			{
				return;
			}
			//todo search support, we'll think about local vs network before doing anything here
			callback(null);
		}

		public void GetCoverArt(string coverArtId, Action<byte[]> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRouteID<byte[]> dataRoute = GetDataRouteID<byte[]>(eRoutes.GetCoverArt);
			if (dataRoute != null)
			{
				GetData(dataRoute, coverArtId, callback);
			}
			else
			{
				callback(null);
			}
		}

		public void GetRecentlyPlayed(Action<List<PulseObject>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRoute<List<PulseObject>> dataRoute = GetDataRoute<List<PulseObject>>(eRoutes.GetRecentlyPlayed);
			if (dataRoute != null)
			{
				GetData(dataRoute, callback);
			}
			else
			{
				callback(null);
			}
		}

		public void GetTopPlaylists(Action<List<PulsePlaylist>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRoute<List<PulsePlaylist>> dataRoute = GetDataRoute<List<PulsePlaylist>>(eRoutes.GetTopPlaylists);
			if (dataRoute != null)
			{
				GetData(dataRoute, callback);
			}
			else
			{
				callback(null);
			}
		}

		public void GetPopularArtists(Action<List<PulseArtist>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRoute<List<PulseArtist>> dataRoute = GetDataRoute<List<PulseArtist>>(eRoutes.GetPopularArtists);
			if (dataRoute != null)
			{
				GetData(dataRoute, callback);
			}
			else
			{
				callback(null);
			}
		}

		public void GetRecentlyAdded(Action<List<PulseObject>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRoute<List<PulseObject>> dataRoute = GetDataRoute<List<PulseObject>>(eRoutes.GetRecentlyAdded);
			if (dataRoute != null)
			{
				GetData(dataRoute, callback);
			}
			else
			{
				Log.Error("Error no data path for: " + eRoutes.GetRecentlyAdded.ToString());
				callback(null);
			}
		}

		public void GetTopItems(Action<List<PulseObject>> callback)
		{
			if (callback == null)
			{
				return;
			}
			m_pulseClient.GetTopItems(callback);
		}

		public void GetFavories(Action<List<PulseObject>> callback)
		{
			if (callback == null)
			{
				return;
			}
			DataRoute<List<PulseObject>> dataRoute = GetDataRoute<List<PulseObject>>(eRoutes.GetFavorites);
			if (dataRoute != null)
			{
				GetData(dataRoute, callback);
			}
			else
			{
				callback(null);
			}
		}
	}
}
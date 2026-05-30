using System;
using System.Collections.Generic;
using System.Text.Json;

namespace Thump.Pulse
{
	public class PulseAPI
	{
		public enum eServerType
		{
			Subsonic,
			Pulse
		}

		private SubsonicAPI m_subsonic;

		public PulseAPI()
		{
			m_subsonic = new SubsonicAPI();
		}

		public void SetServerParams(string ip, string port, string username, string password, SubsonicAPI.eSubSonicAuthType authType, bool enableSSL)
		{
			m_subsonic.SetServerParams(ip, port, username, password, authType, enableSSL);
		}

		public bool TestConnection(out JsonElement response)
		{
			return m_subsonic.TestConnection(out response);
		}

		public bool IsOnline()
		{
			return m_subsonic.IsOnline();
		}

		public string BuildStreamUrl(string trackId)
		{
			return m_subsonic.BuildStreamUrl(trackId);
		}

		public string BuildRestUrl(string endpoint, string extraParams = null)
		{
			return m_subsonic.BuildRestUrl(endpoint, extraParams);
		}

		public void GetArtists(Action<List<PulseArtist>> onComplete)
		{
			m_subsonic.GetArtists(onComplete);
		}

		public void GetPodcasts(Action<List<PulsePodcastChannel>> onComplete)
		{
			m_subsonic.GetPodcasts(onComplete);
		}

		public void Search(string query, Action<PulseSearchData> onComplete)
		{
			m_subsonic.Search(query, onComplete);
		}

		public void GetArtistAlbums(string artistId, Action<List<PulseAlbum>> onComplete)
		{
			m_subsonic.GetArtistAlbums(artistId, onComplete);
		}

		public void GetAlbum(string albumId, Action<PulseAlbum> onComplete)
		{
			m_subsonic.GetAlbum(albumId, onComplete);
		}

		public void GetAlbums(Action<List<PulseAlbum>> onComplete)
		{
			m_subsonic.GetAlbums(onComplete);
		}

		public void CreatePlaylist(string name, Action<PulsePlaylist> onComplete)
		{
			m_subsonic.CreatePlaylist(name, onComplete);
		}

		public void RenamePlaylist(string playlistId, string newName, Action<bool> onComplete)
		{
			m_subsonic.RenamePlaylist(playlistId, newName, onComplete);
		}

		public void Star(string trackId, Action<bool> onComplete)
		{
			m_subsonic.Star(trackId, onComplete);
		}

		public void Unstar(string trackId, Action<bool> onComplete)
		{
			m_subsonic.Unstar(trackId, onComplete);
		}

		public void DeletePlaylist(string playlistId, Action<bool> onComplete)
		{
			m_subsonic.DeletePlaylist(playlistId, onComplete);
		}

		public void AddTrackToPlaylist(string playlistId, string songId, Action<bool> onComplete)
		{
			m_subsonic.AddTrackToPlaylist(playlistId, songId, onComplete);
		}

		public void RemoveTrackFromPlaylist(string playlistId, int songIndex, Action<bool> onComplete)
		{
			m_subsonic.RemoveTrackFromPlaylist(playlistId, songIndex, onComplete);
		}

		public void ReorderPlaylist(string playlistId, int fromIndex, int toIndex, List<PulseTrack> newOrder, Action<bool> onComplete)
		{
			m_subsonic.ReorderPlaylist(playlistId, fromIndex, toIndex, newOrder, onComplete);
		}

		public void MarkPlaylistPlayed(string playlistId, Action<bool> onComplete)
		{
			m_subsonic.MarkPlaylistPlayed(playlistId, onComplete);
		}

		public void GetPlaylists(Action<List<PulsePlaylist>> onComplete)
		{
			m_subsonic.GetPlaylists(onComplete);
		}

		public void GetPlaylist(string playlistId, Action<PulsePlaylist> onComplete)
		{
			m_subsonic.GetPlaylist(playlistId, onComplete);
		}

		public void GetCoverArt(string coverArtId, Action<byte[]> onComplete)
		{
			m_subsonic.GetCoverArt(coverArtId, onComplete);
		}

		public void GetTrackAudio(string trackId, Action<byte[]> onComplete)
		{
			m_subsonic.GetTrackAudio(trackId, onComplete);
		}

		public void GetRecentlyPlayed(Action<List<PulseObject>> onComplete)
		{
			m_subsonic.GetRecentlyPlayed(onComplete);
		}

		public void GetPopularArtists(Action<List<PulseArtist>> onComplete)
		{
			m_subsonic.GetPopularArtists(onComplete);
		}

		public void GetTopPlaylists(Action<List<PulsePlaylist>> onComplete)
		{
			m_subsonic.GetTopPlaylists(onComplete);
		}

		public void GetRecentPlaylists(Action<List<PulsePlaylist>> onComplete)
		{
			m_subsonic.GetRecentPlaylists(onComplete);
		}

		public void GetRecentlyAdded(Action<List<PulseObject>> onComplete)
		{
			m_subsonic.GetRecentlyAdded(onComplete);
		}

		public void GetGenres(Action<List<PulseGenre>> onComplete)
		{
			m_subsonic.GetGenres(onComplete);
		}

		public void GetTopItems(Action<List<PulseObject>> onComplete)
		{
			m_subsonic.GetTopItems(onComplete);
		}

		public void GetTracksForGenre(string genre, Action<List<PulseTrack>> onComplete)
		{
			m_subsonic.GetTracksForGenre(genre, onComplete);
		}

		public void GetFavorites(Action<List<PulseTrack>> onComplete)
		{
			m_subsonic.GetFavorites(onComplete);
		}
	}
}

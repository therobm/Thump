using System.Collections.Generic;
using AndroidX.Concurrent.Futures;
using AndroidX.Media3.Common;
using AndroidX.Media3.Session;
using Google.Common.Util.Concurrent;
using Thump.Data;
using Thump.Pulse;

namespace Thump.Playback
{
	public class ThumpLibraryCallback : Java.Lang.Object, MediaLibraryService.MediaLibrarySession.ICallback
	{
		private const string s_rootId = "root";
		private const string s_albumsId = "albums";
		private const string s_playlistsId = "playlists";
		private const string s_artistsId = "artists";
		private const string s_genresId = "genres";

		private ThumpData m_serviceData;

		public ThumpLibraryCallback(ThumpData data)
		{
			m_serviceData = data;
		}

		public MediaSession.ConnectionResult OnConnect(MediaSession session, MediaSession.ControllerInfo controller)
		{
			return MediaSession.ConnectionResult.Accept(MediaSession.ConnectionResult.DefaultSessionAndLibraryCommands, MediaSession.ConnectionResult.DefaultPlayerCommands);
		}

		public void OnPostConnect(MediaSession session, MediaSession.ControllerInfo controller)
		{
		}

		public void OnDisconnected(MediaSession session, MediaSession.ControllerInfo controller)
		{
		}

		public int OnPlayerCommandRequest(MediaSession session, MediaSession.ControllerInfo controller, int playerCommand)
		{
			// 0 == SessionResult.RESULT_SUCCESS: allow every player command.
			return 0;
		}

		public void OnPlayerInteractionFinished(MediaSession session, MediaSession.ControllerInfo controller, PlayerCommands playerCommands)
		{
		}


		public IListenableFuture OnGetLibraryRoot(MediaLibraryService.MediaLibrarySession session, MediaSession.ControllerInfo browser, MediaLibraryService.LibraryParams libraryParams)
		{
			MediaItem root = BuildBrowsableItem(s_rootId, "Thump");
			return ImmediateFuture(LibraryResult.OfItem(root, libraryParams));
		}

		public IListenableFuture OnGetItem(MediaLibraryService.MediaLibrarySession session, MediaSession.ControllerInfo browser, string mediaId)
		{
			MediaItem item = BuildItemForId(mediaId);
			return ImmediateFuture(LibraryResult.OfItem(item, null));
		}

		public IListenableFuture OnGetChildren(MediaLibraryService.MediaLibrarySession session, MediaSession.ControllerInfo browser, string parentId, int page, int pageSize, MediaLibraryService.LibraryParams libraryParams)
		{
			if (parentId == s_rootId)
			{
				List<MediaItem> categories = new List<MediaItem>();
				categories.Add(BuildBrowsableItem(s_albumsId, "Albums"));
				categories.Add(BuildBrowsableItem(s_playlistsId, "Playlists"));
				categories.Add(BuildBrowsableItem(s_artistsId, "Artists"));
				categories.Add(BuildBrowsableItem(s_genresId, "Genres"));
				return ImmediateFuture(LibraryResult.OfItemList(categories, libraryParams));
			}

			ChildrenResolver resolver = new ChildrenResolver(this, parentId, libraryParams);
			return (IListenableFuture)CallbackToFutureAdapter.GetFuture(resolver);
		}

		private void LoadChildren(string parentId, MediaLibraryService.LibraryParams libraryParams, CallbackToFutureAdapter.Completer completer)
		{
			if (parentId == s_albumsId)
			{
				m_serviceData.GetAlbums((albums) =>
				{
					List<MediaItem> items = new List<MediaItem>();
					if (albums != null)
					{
						for (int idx = 0; idx < albums.Count; idx++)
						{
							PulseAlbum album = albums[idx];
							items.Add(BuildBrowsableItem("album/" + album.Id, album.Name));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, libraryParams));
				});
				return;
			}
			if (parentId == s_playlistsId)
			{
				m_serviceData.GetPlaylists((playlists) =>
				{
					List<MediaItem> items = new List<MediaItem>();
					if (playlists != null)
					{
						for (int idx = 0; idx < playlists.Count; idx++)
						{
							PulsePlaylist playlist = playlists[idx];
							items.Add(BuildBrowsableItem("playlist/" + playlist.Id, playlist.Name));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, libraryParams));
				});
				return;
			}
			if (parentId == s_artistsId)
			{
				m_serviceData.GetArtists((artists) =>
				{
					List<MediaItem> items = new List<MediaItem>();
					if (artists != null)
					{
						for (int idx = 0; idx < artists.Count; idx++)
						{
							PulseArtist artist = artists[idx];
							items.Add(BuildBrowsableItem("artist/" + artist.Id, artist.Name));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, libraryParams));
				});
				return;
			}
			if (parentId == s_genresId)
			{
				m_serviceData.GetGenres((genres) =>
				{
					List<MediaItem> items = new List<MediaItem>();
					if (genres != null)
					{
						for (int idx = 0; idx < genres.Count; idx++)
						{
							PulseGenre genre = genres[idx];
							items.Add(BuildBrowsableItem("genre/" + genre.Name, genre.Name));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, libraryParams));
				});
				return;
			}

			string prefix = ParsePrefix(parentId);
			string value = ParseValue(parentId);
			if (prefix == "album")
			{
				m_serviceData.GetAlbum(value, (album) =>
				{
					completer.Set(LibraryResult.OfItemList(BuildTrackItems(album.Songs), libraryParams));
				});
				return;
			}
			if (prefix == "playlist")
			{
				m_serviceData.GetPlaylist(value, (playlist) =>
				{
					completer.Set(LibraryResult.OfItemList(BuildTrackItems(playlist.Songs), libraryParams));
				});
				return;
			}
			if (prefix == "artist")
			{
				PulseArtist artist = new PulseArtist();
				artist.Id = value;
				m_serviceData.GetAlbumsForArtist(artist, (albums) =>
				{
					List<MediaItem> items = new List<MediaItem>();
					if (albums != null)
					{
						for (int idx = 0; idx < albums.Count; idx++)
						{
							PulseAlbum album = albums[idx];
							items.Add(BuildBrowsableItem("album/" + album.Id, album.Name));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, libraryParams));
				});
				return;
			}
			if (prefix == "genre")
			{
				PulseGenre genre = new PulseGenre();
				genre.Name = value;
				m_serviceData.GetTracksForGenre(genre, (tracks) =>
				{
					completer.Set(LibraryResult.OfItemList(BuildTrackItems(tracks), libraryParams));
				});
				return;
			}

			completer.Set(LibraryResult.OfItemList(new List<MediaItem>(), libraryParams));
		}

		public IListenableFuture OnAddMediaItems(MediaSession session, MediaSession.ControllerInfo controller, IList<MediaItem> mediaItems)
		{
			AddItemsResolver resolver = new AddItemsResolver(this, mediaItems);
			return (IListenableFuture)CallbackToFutureAdapter.GetFuture(resolver);
		}

		private void ResolveItems(IList<MediaItem> items, int index, Java.Util.ArrayList resolved, CallbackToFutureAdapter.Completer completer)
		{
			if (index >= items.Count)
			{
				completer.Set(resolved);
				return;
			}
			MediaItem item = items[index];
			string trackId = StripTrackPrefix(item.MediaId);
			if (string.IsNullOrEmpty(trackId))
			{
				resolved.Add(item);
				ResolveItems(items, index + 1, resolved, completer);
				return;
			}
			PulseTrack track = new PulseTrack();
			track.Id = trackId;
			m_serviceData.GetTrackAudioFile(track, (localPath) =>
			{
				MediaItem resolvedItem = item;
				if (!string.IsNullOrEmpty(localPath))
				{
					Android.Net.Uri uri = Android.Net.Uri.FromFile(new Java.IO.File(localPath));
					resolvedItem = item.BuildUpon().SetUri(uri).Build();
				}
				resolved.Add(resolvedItem);
				ResolveItems(items, index + 1, resolved, completer);
			});
		}

		public IListenableFuture OnSetMediaItems(MediaSession session, MediaSession.ControllerInfo controller, IList<MediaItem> mediaItems, int startIndex, long startPositionMs)
		{
			SetItemsResolver resolver = new SetItemsResolver(this, mediaItems, startIndex, startPositionMs);
			return (IListenableFuture)CallbackToFutureAdapter.GetFuture(resolver);
		}

		private void ResolveSetItems(IList<MediaItem> items, int index, List<MediaItem> resolved, int startIndex, long startPositionMs, CallbackToFutureAdapter.Completer completer)
		{
			if (index >= items.Count)
			{
				MediaSession.MediaItemsWithStartPosition result = new MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs);
				completer.Set(result);
				return;
			}
			MediaItem item = items[index];
			string trackId = StripTrackPrefix(item.MediaId);
			if (string.IsNullOrEmpty(trackId))
			{
				resolved.Add(item);
				ResolveSetItems(items, index + 1, resolved, startIndex, startPositionMs, completer);
				return;
			}
			PulseTrack track = new PulseTrack();
			track.Id = trackId;
			m_serviceData.GetTrackAudioFile(track, (localPath) =>
			{
				MediaItem resolvedItem = item;
				if (!string.IsNullOrEmpty(localPath))
				{
					Android.Net.Uri uri = Android.Net.Uri.FromFile(new Java.IO.File(localPath));
					resolvedItem = item.BuildUpon().SetUri(uri).Build();
				}
				resolved.Add(resolvedItem);
				ResolveSetItems(items, index + 1, resolved, startIndex, startPositionMs, completer);
			});
		}

		private static IListenableFuture ImmediateFuture(Java.Lang.Object value)
		{
			ImmediateResolver resolver = new ImmediateResolver(value);
			return (IListenableFuture)CallbackToFutureAdapter.GetFuture(resolver);
		}

		private static List<MediaItem> BuildTrackItems(List<PulseTrack> tracks)
		{
			List<MediaItem> items = new List<MediaItem>();
			if (tracks == null)
			{
				return items;
			}
			for (int idx = 0; idx < tracks.Count; idx++)
			{
				PulseTrack track = tracks[idx];
				items.Add(BuildPlayableItem("track/" + track.Id, track.Title, track.Artist));
			}
			return items;
		}

		private static MediaItem BuildItemForId(string mediaId)
		{
			string trackId = StripTrackPrefix(mediaId);
			if (!string.IsNullOrEmpty(trackId))
			{
				return BuildPlayableItem(mediaId, mediaId, "");
			}
			return BuildBrowsableItem(mediaId, mediaId);
		}

		private static MediaItem BuildBrowsableItem(string mediaId, string title)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(title);
			metadata.SetIsBrowsable(Java.Lang.Boolean.True);
			metadata.SetIsPlayable(Java.Lang.Boolean.False);

			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(mediaId);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
		}

		private static MediaItem BuildPlayableItem(string mediaId, string title, string subtitle)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(title);
			metadata.SetArtist(subtitle);
			metadata.SetIsBrowsable(Java.Lang.Boolean.False);
			metadata.SetIsPlayable(Java.Lang.Boolean.True);

			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(mediaId);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
		}

		private static string ParsePrefix(string mediaId)
		{
			int slash = mediaId.IndexOf('/');
			if (slash < 0)
			{
				return mediaId;
			}
			return mediaId.Substring(0, slash);
		}

		private static string ParseValue(string mediaId)
		{
			int slash = mediaId.IndexOf('/');
			if (slash < 0)
			{
				return "";
			}
			return mediaId.Substring(slash + 1);
		}

		private static string StripTrackPrefix(string mediaId)
		{
			if (string.IsNullOrEmpty(mediaId))
			{
				return null;
			}
			if (!mediaId.StartsWith("track/"))
			{
				return null;
			}
			return mediaId.Substring("track/".Length);
		}

		private class ImmediateResolver : Java.Lang.Object, CallbackToFutureAdapter.IResolver
		{
			private Java.Lang.Object m_value;

			public ImmediateResolver(Java.Lang.Object value)
			{
				m_value = value;
			}

			public Java.Lang.Object AttachCompleter(CallbackToFutureAdapter.Completer completer)
			{
				completer.Set(m_value);
				return null;
			}
		}

		private class ChildrenResolver : Java.Lang.Object, CallbackToFutureAdapter.IResolver
		{
			private ThumpLibraryCallback m_owner;
			private string m_parentId;
			private MediaLibraryService.LibraryParams m_params;

			public ChildrenResolver(ThumpLibraryCallback owner, string parentId, MediaLibraryService.LibraryParams libraryParams)
			{
				m_owner = owner;
				m_parentId = parentId;
				m_params = libraryParams;
			}

			public Java.Lang.Object AttachCompleter(CallbackToFutureAdapter.Completer completer)
			{
				m_owner.LoadChildren(m_parentId, m_params, completer);
				return null;
			}
		}

		private class AddItemsResolver : Java.Lang.Object, CallbackToFutureAdapter.IResolver
		{
			private ThumpLibraryCallback m_owner;
			private IList<MediaItem> m_items;

			public AddItemsResolver(ThumpLibraryCallback owner, IList<MediaItem> items)
			{
				m_owner = owner;
				m_items = items;
			}

			public Java.Lang.Object AttachCompleter(CallbackToFutureAdapter.Completer completer)
			{
				Java.Util.ArrayList resolved = new Java.Util.ArrayList();
				m_owner.ResolveItems(m_items, 0, resolved, completer);
				return null;
			}
		}

		private class SetItemsResolver : Java.Lang.Object, CallbackToFutureAdapter.IResolver
		{
			private ThumpLibraryCallback m_owner;
			private IList<MediaItem> m_items;
			private int m_startIndex;
			private long m_startPositionMs;

			public SetItemsResolver(ThumpLibraryCallback owner, IList<MediaItem> items, int startIndex, long startPositionMs)
			{
				m_owner = owner;
				m_items = items;
				m_startIndex = startIndex;
				m_startPositionMs = startPositionMs;
			}

			public Java.Lang.Object AttachCompleter(CallbackToFutureAdapter.Completer completer)
			{
				List<MediaItem> resolved = new List<MediaItem>();
				m_owner.ResolveSetItems(m_items, 0, resolved, m_startIndex, m_startPositionMs, completer);
				return null;
			}
		}
	}
}

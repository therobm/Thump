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
		private const string s_homeId = "home";
		private const string s_libraryId = "library";
		private const string s_podcastsId = "podcasts";
		private const string s_albumsId = "albums";
		private const string s_playlistsId = "playlists";
		private const string s_artistsId = "artists";
		private const string s_genresId = "genres";
		private const string s_recentlyPlayedId = "home_recent";
		private const string s_recentlyAddedId = "home_added";
		private const string s_topPlaylistsId = "home_top";
		private const string s_popularArtistsId = "home_popular";

		private ThumpData m_serviceData;
		private QueuePrefetcher m_prefetcher;

		public ThumpLibraryCallback(ThumpData data, QueuePrefetcher prefetcher)
		{
			m_serviceData = data;
			m_prefetcher = prefetcher;
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
			bool isCarController = session.IsAutoCompanionController(controller) || session.IsAutomotiveController(controller);
			if (!isCarController)
			{
				return;
			}
			IPlayer player = session.Player;
			if (player == null)
			{
				return;
			}
			if (!player.IsPlaying)
			{
				return;
			}
			player.Pause();
		}

		public int OnPlayerCommandRequest(MediaSession session, MediaSession.ControllerInfo controller, int playerCommand)
		{
			// 0 == SessionResult.RESULT_SUCCESS: allow every player command.
			return 0;
		}

		public void OnPlayerInteractionFinished(MediaSession session, MediaSession.ControllerInfo controller, PlayerCommands playerCommands)
		{
		}

		public IListenableFuture OnPlaybackResumption(MediaSession mediaSession, MediaSession.ControllerInfo controller, bool isForPlayback)
		{
			// No saved-session resumption yet; decline so the framework doesn't try to resume.
			FailedResolver resolver = new FailedResolver();
			return (IListenableFuture)CallbackToFutureAdapter.GetFuture(resolver);
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

		public IListenableFuture OnSubscribe(MediaLibraryService.MediaLibrarySession session, MediaSession.ControllerInfo browser, string parentId, MediaLibraryService.LibraryParams libraryParams)
		{
			return ImmediateFuture(LibraryResult.OfVoid(libraryParams));
		}

		public IListenableFuture OnUnsubscribe(MediaLibraryService.MediaLibrarySession session, MediaSession.ControllerInfo browser, string parentId)
		{
			return ImmediateFuture(LibraryResult.OfVoid(null));
		}

		public IListenableFuture OnGetChildren(MediaLibraryService.MediaLibrarySession session, MediaSession.ControllerInfo browser, string parentId, int page, int pageSize, MediaLibraryService.LibraryParams libraryParams)
		{
			if (parentId == s_rootId)
			{
				List<MediaItem> categories = new List<MediaItem>();
				categories.Add(BuildBrowsableItem(s_homeId, "Home"));
				categories.Add(BuildBrowsableItem(s_playlistsId, "Playlists"));
				categories.Add(BuildBrowsableItem(s_libraryId, "Library"));
				categories.Add(BuildBrowsableItem(s_podcastsId, "Podcasts"));
				return ImmediateFuture(LibraryResult.OfItemList(categories, BuildContentStyleParams()));
			}

			ChildrenResolver resolver = new ChildrenResolver(this, parentId, libraryParams);
			return (IListenableFuture)CallbackToFutureAdapter.GetFuture(resolver);
		}

		private void LoadChildren(string parentId, MediaLibraryService.LibraryParams libraryParams, OneShotCompleter completer)
		{
			MediaLibraryService.LibraryParams styleParams = BuildContentStyleParams();
			if (parentId == s_homeId)
			{
				List<MediaItem> combined = new List<MediaItem>();
				object gate = new object();
				int pendingShelves = 4;
				bool recentlyPlayedDelivered = false;
				bool recentlyAddedDelivered = false;
				bool topPlaylistsDelivered = false;
				bool popularArtistsDelivered = false;

				m_serviceData.GetRecentlyPlayed((objects) =>
				{
					lock (gate)
					{
						if (recentlyPlayedDelivered)
						{
							return;
						}
						recentlyPlayedDelivered = true;
						combined.AddRange(BuildMixedItemsGrouped(objects, "Recently Played"));
						pendingShelves = pendingShelves - 1;
						if (pendingShelves == 0)
						{
							completer.Set(LibraryResult.OfItemList(combined, styleParams));
						}
					}
				});
				m_serviceData.GetRecentlyAdded((objects) =>
				{
					lock (gate)
					{
						if (recentlyAddedDelivered)
						{
							return;
						}
						recentlyAddedDelivered = true;
						combined.AddRange(BuildMixedItemsGrouped(objects, "Recently Added"));
						pendingShelves = pendingShelves - 1;
						if (pendingShelves == 0)
						{
							completer.Set(LibraryResult.OfItemList(combined, styleParams));
						}
					}
				});
				m_serviceData.GetTopPlaylists((playlists) =>
				{
					lock (gate)
					{
						if (topPlaylistsDelivered)
						{
							return;
						}
						topPlaylistsDelivered = true;
						if (playlists != null)
						{
							for (int idx = 0; idx < playlists.Count; idx++)
							{
								PulsePlaylist playlist = playlists[idx];
								combined.Add(BuildBrowsableItemGrouped("playlist/" + playlist.Id, playlist.Name, playlist.CoverArt, "Top Playlists"));
							}
						}
						pendingShelves = pendingShelves - 1;
						if (pendingShelves == 0)
						{
							completer.Set(LibraryResult.OfItemList(combined, styleParams));
						}
					}
				});
				m_serviceData.GetPopularArtists((artists) =>
				{
					lock (gate)
					{
						if (popularArtistsDelivered)
						{
							return;
						}
						popularArtistsDelivered = true;
						if (artists != null)
						{
							for (int idx = 0; idx < artists.Count; idx++)
							{
								PulseArtist artist = artists[idx];
								combined.Add(BuildBrowsableItemGrouped("artist/" + artist.Id, artist.Name, artist.CoverArt, "Popular Artists"));
							}
						}
						pendingShelves = pendingShelves - 1;
						if (pendingShelves == 0)
						{
							completer.Set(LibraryResult.OfItemList(combined, styleParams));
						}
					}
				});
				return;
			}
			if (parentId == s_podcastsId)
			{
				List<MediaItem> items = new List<MediaItem>();
				items.Add(BuildBrowsableItem("podcasts_soon", "Coming soon"));
				completer.Set(LibraryResult.OfItemList(items, styleParams));
				return;
			}
			if (parentId == s_libraryId)
			{
				List<MediaItem> categories = new List<MediaItem>();
				categories.Add(BuildBrowsableItem(s_artistsId, "Artists"));
				categories.Add(BuildBrowsableItem(s_albumsId, "Albums"));
				categories.Add(BuildBrowsableItem(s_genresId, "Genres"));
				completer.Set(LibraryResult.OfItemList(categories, styleParams));
				return;
			}
			if (parentId == s_recentlyPlayedId)
			{
				bool delivered = false;
				m_serviceData.GetRecentlyPlayed((objects) =>
				{
					if (delivered)
					{
						return;
					}
					delivered = true;
					completer.Set(LibraryResult.OfItemList(BuildMixedItems(objects), styleParams));
				});
				return;
			}
			if (parentId == s_recentlyAddedId)
			{
				bool delivered = false;
				m_serviceData.GetRecentlyAdded((objects) =>
				{
					if (delivered)
					{
						return;
					}
					delivered = true;
					completer.Set(LibraryResult.OfItemList(BuildMixedItems(objects), styleParams));
				});
				return;
			}
			if (parentId == s_topPlaylistsId)
			{
				bool delivered = false;
				m_serviceData.GetTopPlaylists((playlists) =>
				{
					if (delivered)
					{
						return;
					}
					delivered = true;
					List<MediaItem> items = new List<MediaItem>();
					if (playlists != null)
					{
						for (int idx = 0; idx < playlists.Count; idx++)
						{
							PulsePlaylist playlist = playlists[idx];
							items.Add(BuildBrowsableItem("playlist/" + playlist.Id, playlist.Name, playlist.CoverArt));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, styleParams));
				});
				return;
			}
			if (parentId == s_popularArtistsId)
			{
				bool delivered = false;
				m_serviceData.GetPopularArtists((artists) =>
				{
					if (delivered)
					{
						return;
					}
					delivered = true;
					List<MediaItem> items = new List<MediaItem>();
					if (artists != null)
					{
						for (int idx = 0; idx < artists.Count; idx++)
						{
							PulseArtist artist = artists[idx];
							items.Add(BuildBrowsableItem("artist/" + artist.Id, artist.Name, artist.CoverArt));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, styleParams));
				});
				return;
			}
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
							items.Add(BuildAlbumItem("album/" + album.Id, album.Name, album.Artist, album.CoverArt));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, styleParams));
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
							items.Add(BuildBrowsableItem("playlist/" + playlist.Id, playlist.Name, playlist.CoverArt));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, styleParams));
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
							items.Add(BuildBrowsableItem("artist/" + artist.Id, artist.Name, artist.CoverArt));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, styleParams));
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
					completer.Set(LibraryResult.OfItemList(items, styleParams));
				});
				return;
			}

			string prefix = ParsePrefix(parentId);
			string value = ParseValue(parentId);
			if (prefix == "album")
			{
				m_serviceData.GetAlbum(value, (album) =>
				{
					List<PulseTrack> songs;
					if (album == null)
					{
						songs = new List<PulseTrack>();
					}
					else
					{
						songs = album.Songs;
					}
					completer.Set(LibraryResult.OfItemList(BuildCollectionItems("albumplay/" + value, "albumshuffle/" + value, songs), styleParams));
				});
				return;
			}
			if (prefix == "playlist")
			{
				m_serviceData.GetPlaylist(value, (playlist) =>
				{
					List<PulseTrack> songs;
					if (playlist == null)
					{
						songs = new List<PulseTrack>();
					}
					else
					{
						songs = playlist.Songs;
					}
					completer.Set(LibraryResult.OfItemList(BuildCollectionItems("playlistplay/" + value, "playlistshuffle/" + value, songs), styleParams));
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
					items.Add(BuildPlayableItem("artistplay/" + value, "Play all", ""));
					items.Add(BuildPlayableItem("artistshuffle/" + value, "Shuffle", ""));
					if (albums != null)
					{
						for (int idx = 0; idx < albums.Count; idx++)
						{
							PulseAlbum album = albums[idx];
							items.Add(BuildAlbumItem("album/" + album.Id, album.Name, album.Artist, album.CoverArt));
						}
					}
					completer.Set(LibraryResult.OfItemList(items, styleParams));
				});
				return;
			}
			if (prefix == "genre")
			{
				PulseGenre genre = new PulseGenre();
				genre.Name = value;
				m_serviceData.GetTracksForGenre(genre, (tracks) =>
				{
					completer.Set(LibraryResult.OfItemList(BuildCollectionItems("genreplay/" + value, "genreshuffle/" + value, tracks), styleParams));
				});
				return;
			}

			completer.Set(LibraryResult.OfItemList(new List<MediaItem>(), styleParams));
		}

		public IListenableFuture OnAddMediaItems(MediaSession session, MediaSession.ControllerInfo controller, IList<MediaItem> mediaItems)
		{
			AddItemsResolver resolver = new AddItemsResolver(this, mediaItems);
			return (IListenableFuture)CallbackToFutureAdapter.GetFuture(resolver);
		}

		private void ResolveItems(IList<MediaItem> items, int index, Java.Util.ArrayList resolved, OneShotCompleter completer)
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
			m_serviceData.EnsureTrackAvailability(track, (isAvailable) =>
			{
				MediaItem resolvedItem = item;
				if (isAvailable)
				{
					Android.Net.Uri uri = MediaItemBuilder.GetURI(track);
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

		private void ResolveSetItems(IList<MediaItem> items, int index, List<MediaItem> resolved, int startIndex, long startPositionMs, OneShotCompleter completer)
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
			m_serviceData.EnsureTrackAvailability(track, (isAvailable) =>
			{
				MediaItem resolvedItem = item;
				if (isAvailable)
				{
					Android.Net.Uri uri = MediaItemBuilder.GetURI(track);
					resolvedItem = item.BuildUpon().SetUri(uri).Build();
				}
				resolved.Add(resolvedItem);
				ResolveSetItems(items, index + 1, resolved, startIndex, startPositionMs, completer);
			});
		}

		private bool TryExpandSetCollection(IList<MediaItem> items, OneShotCompleter completer)
		{
			if (items == null)
			{
				return false;
			}
			if (items.Count != 1)
			{
				return false;
			}
			CollectionRequest request = ParseCollectionRequest(items[0].MediaId);
			if (!request.IsCollection)
			{
				return false;
			}
			FetchCollectionTracks(request, (songs) =>
			{
				m_prefetcher.LoadCollection(songs, 0, (startItem) =>
				{
					if (startItem == null)
					{
						MediaSession.MediaItemsWithStartPosition empty = new MediaSession.MediaItemsWithStartPosition(new List<MediaItem>(), 0, 0);
						completer.Set(empty);
						return;
					}
					List<MediaItem> single = new List<MediaItem>();
					single.Add(startItem);
					MediaSession.MediaItemsWithStartPosition result = new MediaSession.MediaItemsWithStartPosition(single, 0, 0);
					completer.Set(result);
				});
			});
			return true;
		}

		private bool TryExpandAddCollection(IList<MediaItem> items, OneShotCompleter completer)
		{
			if (items == null)
			{
				return false;
			}
			if (items.Count != 1)
			{
				return false;
			}
			CollectionRequest request = ParseCollectionRequest(items[0].MediaId);
			if (!request.IsCollection)
			{
				return false;
			}
			FetchCollectionTracks(request, (songs) =>
			{
				List<MediaItem> trackItems = BuildTrackItems(songs);
				Java.Util.ArrayList resolved = new Java.Util.ArrayList();
				ResolveItems(trackItems, 0, resolved, completer);
			});
			return true;
		}

		private void FetchCollectionTracks(CollectionRequest request, System.Action<List<PulseTrack>> onTracks)
		{
			if (request.Kind == eCollectionKind.Playlist)
			{
				m_serviceData.GetPlaylist(request.Id, (playlist) =>
				{
					List<PulseTrack> songs;
					if (playlist == null)
					{
						songs = new List<PulseTrack>();
					}
					else
					{
						songs = playlist.Songs;
					}
					DeliverCollectionTracks(request, songs, onTracks);
				});
				return;
			}
			if (request.Kind == eCollectionKind.Genre)
			{
				PulseGenre genre = new PulseGenre();
				genre.Name = request.Id;
				m_serviceData.GetTracksForGenre(genre, (tracks) =>
				{
					DeliverCollectionTracks(request, tracks, onTracks);
				});
				return;
			}
			if (request.Kind == eCollectionKind.Artist)
			{
				FetchArtistCollectionTracks(request, onTracks);
				return;
			}
			m_serviceData.GetAlbum(request.Id, (album) =>
			{
				List<PulseTrack> songs;
				if (album == null)
				{
					songs = new List<PulseTrack>();
				}
				else
				{
					songs = album.Songs;
				}
				DeliverCollectionTracks(request, songs, onTracks);
			});
		}

		private void FetchArtistCollectionTracks(CollectionRequest request, System.Action<List<PulseTrack>> onTracks)
		{
			PulseArtist artist = new PulseArtist();
			artist.Id = request.Id;
			m_serviceData.GetAlbumsForArtist(artist, (albums) =>
			{
				List<PulseAlbum> albumList;
				if (albums == null)
				{
					albumList = new List<PulseAlbum>();
				}
				else
				{
					albumList = albums;
				}
				int albumCount = albumList.Count;
				if (albumCount == 0)
				{
					DeliverCollectionTracks(request, new List<PulseTrack>(), onTracks);
					return;
				}
				object gate = new object();
				List<List<PulseTrack>> albumSongs = new List<List<PulseTrack>>();
				bool[] albumDelivered = new bool[albumCount];
				for (int idx = 0; idx < albumCount; idx++)
				{
					albumSongs.Add(new List<PulseTrack>());
					albumDelivered[idx] = false;
				}
				int pendingAlbums = albumCount;
				for (int idx = 0; idx < albumCount; idx++)
				{
					int albumIndex = idx;
					PulseAlbum album = albumList[albumIndex];
					m_serviceData.GetAlbum(album.Id, (fetched) =>
					{
						lock (gate)
						{
							if (albumDelivered[albumIndex])
							{
								return;
							}
							albumDelivered[albumIndex] = true;
							if (fetched != null)
							{
								List<PulseTrack> fetchedSongs = fetched.Songs;
								if (fetchedSongs != null)
								{
									for (int songIdx = 0; songIdx < fetchedSongs.Count; songIdx++)
									{
										albumSongs[albumIndex].Add(fetchedSongs[songIdx]);
									}
								}
							}
							pendingAlbums = pendingAlbums - 1;
							if (pendingAlbums == 0)
							{
								List<PulseTrack> combined = new List<PulseTrack>();
								for (int collectIdx = 0; collectIdx < albumCount; collectIdx++)
								{
									List<PulseTrack> slot = albumSongs[collectIdx];
									for (int songIdx = 0; songIdx < slot.Count; songIdx++)
									{
										combined.Add(slot[songIdx]);
									}
								}
								DeliverCollectionTracks(request, combined, onTracks);
							}
						}
					});
				}
			});
		}

		private static void DeliverCollectionTracks(CollectionRequest request, List<PulseTrack> songs, System.Action<List<PulseTrack>> onTracks)
		{
			List<PulseTrack> ordered;
			if (songs == null)
			{
				ordered = new List<PulseTrack>();
			}
			else
			{
				ordered = songs;
			}
			if (request.IsShuffle)
			{
				List<PulseTrack> shuffled = new List<PulseTrack>();
				for (int idx = 0; idx < ordered.Count; idx++)
				{
					shuffled.Add(ordered[idx]);
				}
				ShuffleTracks(shuffled);
				ordered = shuffled;
			}
			onTracks(ordered);
		}

		private static void ShuffleTracks(List<PulseTrack> tracks)
		{
			System.Random random = new System.Random();
			for (int idx = tracks.Count - 1; idx > 0; idx--)
			{
				int swap = random.Next(idx + 1);
				PulseTrack temporary = tracks[idx];
				tracks[idx] = tracks[swap];
				tracks[swap] = temporary;
			}
		}

		private static CollectionRequest ParseCollectionRequest(string mediaId)
		{
			CollectionRequest request = new CollectionRequest();
			request.IsCollection = false;
			request.Kind = eCollectionKind.Album;
			request.IsShuffle = false;
			request.Id = "";
			if (string.IsNullOrEmpty(mediaId))
			{
				return request;
			}
			if (mediaId.StartsWith("albumplay/"))
			{
				request.IsCollection = true;
				request.Kind = eCollectionKind.Album;
				request.IsShuffle = false;
				request.Id = mediaId.Substring("albumplay/".Length);
				return request;
			}
			if (mediaId.StartsWith("albumshuffle/"))
			{
				request.IsCollection = true;
				request.Kind = eCollectionKind.Album;
				request.IsShuffle = true;
				request.Id = mediaId.Substring("albumshuffle/".Length);
				return request;
			}
			if (mediaId.StartsWith("playlistplay/"))
			{
				request.IsCollection = true;
				request.Kind = eCollectionKind.Playlist;
				request.IsShuffle = false;
				request.Id = mediaId.Substring("playlistplay/".Length);
				return request;
			}
			if (mediaId.StartsWith("playlistshuffle/"))
			{
				request.IsCollection = true;
				request.Kind = eCollectionKind.Playlist;
				request.IsShuffle = true;
				request.Id = mediaId.Substring("playlistshuffle/".Length);
				return request;
			}
			if (mediaId.StartsWith("artistplay/"))
			{
				request.IsCollection = true;
				request.Kind = eCollectionKind.Artist;
				request.IsShuffle = false;
				request.Id = mediaId.Substring("artistplay/".Length);
				return request;
			}
			if (mediaId.StartsWith("artistshuffle/"))
			{
				request.IsCollection = true;
				request.Kind = eCollectionKind.Artist;
				request.IsShuffle = true;
				request.Id = mediaId.Substring("artistshuffle/".Length);
				return request;
			}
			if (mediaId.StartsWith("genreplay/"))
			{
				request.IsCollection = true;
				request.Kind = eCollectionKind.Genre;
				request.IsShuffle = false;
				request.Id = mediaId.Substring("genreplay/".Length);
				return request;
			}
			if (mediaId.StartsWith("genreshuffle/"))
			{
				request.IsCollection = true;
				request.Kind = eCollectionKind.Genre;
				request.IsShuffle = true;
				request.Id = mediaId.Substring("genreshuffle/".Length);
				return request;
			}
			return request;
		}

		private static IListenableFuture ImmediateFuture(Java.Lang.Object value)
		{
			ImmediateResolver resolver = new ImmediateResolver(value);
			return (IListenableFuture)CallbackToFutureAdapter.GetFuture(resolver);
		}

		private static MediaLibraryService.LibraryParams BuildContentStyleParams()
		{
			Android.OS.Bundle extras = new Android.OS.Bundle();
			extras.PutInt(MediaConstants.ExtrasKeyContentStyleBrowsable, MediaConstants.ExtrasValueContentStyleGridItem);
			extras.PutInt(MediaConstants.ExtrasKeyContentStylePlayable, MediaConstants.ExtrasValueContentStyleListItem);
			MediaLibraryService.LibraryParams.Builder builder = new MediaLibraryService.LibraryParams.Builder();
			builder.SetExtras(extras);
			return builder.Build();
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
				items.Add(BuildPlayableItem("track/" + track.Id, track.Title, track.Artist, track.ImageID));
			}
			return items;
		}

		private static List<MediaItem> BuildCollectionItems(string playMediaId, string shuffleMediaId, List<PulseTrack> tracks)
		{
			List<MediaItem> items = new List<MediaItem>();
			items.Add(BuildPlayableItem(playMediaId, "Play all", ""));
			items.Add(BuildPlayableItem(shuffleMediaId, "Shuffle", ""));
			List<MediaItem> trackItems = BuildTrackItems(tracks);
			for (int idx = 0; idx < trackItems.Count; idx++)
			{
				items.Add(trackItems[idx]);
			}
			return items;
		}

		private static List<MediaItem> BuildMixedItems(List<PulseObject> objects)
		{
			List<MediaItem> items = new List<MediaItem>();
			if (objects == null)
			{
				return items;
			}
			for (int idx = 0; idx < objects.Count; idx++)
			{
				PulseObject pulseObject = objects[idx];
				switch (pulseObject.Kind)
				{
					case eDataType.Album:
					{
						PulseAlbum album = (PulseAlbum)pulseObject;
						items.Add(BuildAlbumItem("album/" + album.Id, album.Name, album.Artist, album.CoverArt));
						break;
					}
					case eDataType.Artist:
					{
						PulseArtist artist = (PulseArtist)pulseObject;
						items.Add(BuildBrowsableItem("artist/" + artist.Id, artist.Name, artist.CoverArt));
						break;
					}
					case eDataType.Playlist:
					{
						PulsePlaylist playlist = (PulsePlaylist)pulseObject;
						items.Add(BuildBrowsableItem("playlist/" + playlist.Id, playlist.Name, playlist.CoverArt));
						break;
					}
					case eDataType.Track:
					{
						PulseTrack track = (PulseTrack)pulseObject;
						items.Add(BuildPlayableItem("track/" + track.Id, track.Title, track.Artist, track.ImageID));
						break;
					}
				}
			}
			return items;
		}

		private static List<MediaItem> BuildMixedItemsGrouped(List<PulseObject> objects, string groupTitle)
		{
			List<MediaItem> items = new List<MediaItem>();
			if (objects == null)
			{
				return items;
			}
			for (int idx = 0; idx < objects.Count; idx++)
			{
				PulseObject pulseObject = objects[idx];
				switch (pulseObject.Kind)
				{
					case eDataType.Album:
					{
						PulseAlbum album = (PulseAlbum)pulseObject;
						items.Add(BuildAlbumItemGrouped("album/" + album.Id, album.Name, album.Artist, album.CoverArt, groupTitle));
						break;
					}
					case eDataType.Artist:
					{
						PulseArtist artist = (PulseArtist)pulseObject;
						items.Add(BuildBrowsableItemGrouped("artist/" + artist.Id, artist.Name, artist.CoverArt, groupTitle));
						break;
					}
					case eDataType.Playlist:
					{
						PulsePlaylist playlist = (PulsePlaylist)pulseObject;
						items.Add(BuildBrowsableItemGrouped("playlist/" + playlist.Id, playlist.Name, playlist.CoverArt, groupTitle));
						break;
					}
					case eDataType.Track:
					{
						PulseTrack track = (PulseTrack)pulseObject;
						items.Add(BuildPlayableItemGrouped("track/" + track.Id, track.Title, track.Artist, track.ImageID, groupTitle));
						break;
					}
				}
			}
			return items;
		}

		private static Android.OS.Bundle BuildGroupTitleExtras(string groupTitle)
		{
			Android.OS.Bundle extras = new Android.OS.Bundle();
			extras.PutString(MediaConstants.ExtrasKeyContentStyleGroupTitle, groupTitle);
			extras.PutInt(MediaConstants.ExtrasKeyContentStyleSingleItem, MediaConstants.ExtrasValueContentStyleGridItem);
			return extras;
		}

		private static MediaItem BuildBrowsableItemGrouped(string mediaId, string title, string coverArtId, string groupTitle)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(title);
			metadata.SetIsBrowsable(Java.Lang.Boolean.True);
			metadata.SetIsPlayable(Java.Lang.Boolean.False);
			metadata.SetExtras(BuildGroupTitleExtras(groupTitle));
			Android.Net.Uri artworkUri = BuildArtworkUri(coverArtId);
			if (artworkUri != null)
			{
				metadata.SetArtworkUri(artworkUri);
			}

			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(mediaId);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
		}

		private static MediaItem BuildAlbumItemGrouped(string mediaId, string title, string subtitle, string coverArtId, string groupTitle)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(title);
			metadata.SetSubtitle(subtitle);
			metadata.SetIsBrowsable(Java.Lang.Boolean.True);
			metadata.SetIsPlayable(Java.Lang.Boolean.False);
			metadata.SetExtras(BuildGroupTitleExtras(groupTitle));
			Android.Net.Uri artworkUri = BuildArtworkUri(coverArtId);
			if (artworkUri != null)
			{
				metadata.SetArtworkUri(artworkUri);
			}

			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(mediaId);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
		}

		private static MediaItem BuildPlayableItemGrouped(string mediaId, string title, string subtitle, string coverArtId, string groupTitle)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(title);
			metadata.SetArtist(subtitle);
			metadata.SetIsBrowsable(Java.Lang.Boolean.False);
			metadata.SetIsPlayable(Java.Lang.Boolean.True);
			metadata.SetExtras(BuildGroupTitleExtras(groupTitle));
			Android.Net.Uri artworkUri = BuildArtworkUri(coverArtId);
			if (artworkUri != null)
			{
				metadata.SetArtworkUri(artworkUri);
			}

			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(mediaId);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
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
			return BuildBrowsableItem(mediaId, title, null);
		}

		private static MediaItem BuildBrowsableItem(string mediaId, string title, string coverArtId)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(title);
			metadata.SetIsBrowsable(Java.Lang.Boolean.True);
			metadata.SetIsPlayable(Java.Lang.Boolean.False);
			Android.Net.Uri artworkUri = BuildArtworkUri(coverArtId);
			if (artworkUri != null)
			{
				metadata.SetArtworkUri(artworkUri);
			}

			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(mediaId);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
		}

		private static MediaItem BuildAlbumItem(string mediaId, string title, string subtitle, string coverArtId)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(title);
			metadata.SetSubtitle(subtitle);
			metadata.SetIsBrowsable(Java.Lang.Boolean.True);
			metadata.SetIsPlayable(Java.Lang.Boolean.False);
			Android.Net.Uri artworkUri = BuildArtworkUri(coverArtId);
			if (artworkUri != null)
			{
				metadata.SetArtworkUri(artworkUri);
			}

			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(mediaId);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
		}

		private static MediaItem BuildPlayableItem(string mediaId, string title, string subtitle)
		{
			return BuildPlayableItem(mediaId, title, subtitle, null);
		}

		private static MediaItem BuildPlayableItem(string mediaId, string title, string subtitle, string coverArtId)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(title);
			metadata.SetArtist(subtitle);
			metadata.SetIsBrowsable(Java.Lang.Boolean.False);
			metadata.SetIsPlayable(Java.Lang.Boolean.True);
			Android.Net.Uri artworkUri = BuildArtworkUri(coverArtId);
			if (artworkUri != null)
			{
				metadata.SetArtworkUri(artworkUri);
			}

			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(mediaId);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
		}

		private static Android.Net.Uri BuildArtworkUri(string coverArtId)
		{
			if (string.IsNullOrEmpty(coverArtId))
			{
				return null;
			}
			return Android.Net.Uri.Parse("content://com.therobm.thump.coverart/" + Android.Net.Uri.Encode(coverArtId));
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

		private enum eCollectionKind
		{
			Album,
			Playlist,
			Artist,
			Genre
		}

		private class CollectionRequest
		{
			public bool IsCollection;
			public eCollectionKind Kind;
			public bool IsShuffle;
			public string Id;
		}

		private class OneShotCompleter
		{
			private CallbackToFutureAdapter.Completer m_completer;
			private bool m_done;

			public OneShotCompleter(CallbackToFutureAdapter.Completer completer)
			{
				m_completer = completer;
				m_done = false;
			}

			public void Set(Java.Lang.Object result)
			{
				if (m_done)
				{
					return;
				}
				m_done = true;
				m_completer.Set(result);
			}
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
				OneShotCompleter guard = new OneShotCompleter(completer);
				guard.Set(m_value);
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
				OneShotCompleter guard = new OneShotCompleter(completer);
				m_owner.LoadChildren(m_parentId, m_params, guard);
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
				OneShotCompleter guard = new OneShotCompleter(completer);
				bool handled = m_owner.TryExpandAddCollection(m_items, guard);
				if (handled)
				{
					return null;
				}
				Java.Util.ArrayList resolved = new Java.Util.ArrayList();
				m_owner.ResolveItems(m_items, 0, resolved, guard);
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
				OneShotCompleter guard = new OneShotCompleter(completer);
				bool handled = m_owner.TryExpandSetCollection(m_items, guard);
				if (handled)
				{
					return null;
				}
				List<MediaItem> resolved = new List<MediaItem>();
				m_owner.ResolveSetItems(m_items, 0, resolved, m_startIndex, m_startPositionMs, guard);
				return null;
			}
		}

		private class FailedResolver : Java.Lang.Object, CallbackToFutureAdapter.IResolver
		{
			public Java.Lang.Object AttachCompleter(CallbackToFutureAdapter.Completer completer)
			{
				completer.SetException(new Java.Lang.UnsupportedOperationException("Playback resumption not supported."));
				return null;
			}
		}
	}
}

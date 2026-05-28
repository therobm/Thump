using System.Collections.Generic;
using System.IO;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Storage;
using Thump.Data;
using Thump.Playback;
using Thump.Pulse;
using Thump.Views;

namespace Thump
{
	public enum eTab
	{
		Home,
		Library,
		Search,
		Settings,
	}

	public class MainView : ContentPage
	{
		public const string ServerUrl = "https://192.168.5.5:32458";
		public const string ServerUser = "Rob";

		public static MainView Self { get { return s_self; } }
		public static ThumpData Data { get { return Self.m_data; } }
		
		private static MainView s_self;
		private PulseClient m_pulseClient;
		private ThumpCache m_cache;
		private Grid m_rootGrid;
		private ContentView m_contentHost;
		private HomeView m_homeView;
		private LibraryView m_libraryView;
		private SearchView m_searchView;
		private SettingsView m_settingsView;
		private MiniPlayer m_miniPlayer;
		private NavFooter m_navFooter;

		private eTab m_activeTab = eTab.Home;
		private List<View> m_detailStack = new List<View>();

		private List<PulseTrack> m_currentQueue = new List<PulseTrack>();
		private int m_currentQueueIndex;
		private PulseTrack m_currentTrack;
		private ThumpData m_data;
		private IThumpPlayer m_player;
		private ePlaybackState m_playbackState = ePlaybackState.Idle;
		private long m_currentDurationMs;
		private NowPlayingView m_nowPlayingView;
		private bool m_shuffleEnabled;
		private eRepeatMode m_repeatMode = eRepeatMode.Off;

		public MainView()
		{
			s_self = this;

			Shell.SetNavBarIsVisible(this, false);
			Shell.SetTabBarIsVisible(this, false);
			BackgroundColor = ThumpColors.Background;

			m_rootGrid = new Grid();
			RowDefinition contentRow = new RowDefinition();
			contentRow.Height = GridLength.Star;
			m_rootGrid.RowDefinitions.Add(contentRow);
			RowDefinition miniPlayerRow = new RowDefinition();
			miniPlayerRow.Height = GridLength.Auto;
			m_rootGrid.RowDefinitions.Add(miniPlayerRow);
			RowDefinition navFooterRow = new RowDefinition();
			navFooterRow.Height = GridLength.Auto;
			m_rootGrid.RowDefinitions.Add(navFooterRow);

			m_contentHost = new ContentView();
			Grid.SetRow(m_contentHost, 0);
			m_rootGrid.Children.Add(m_contentHost);

			Content = m_rootGrid;


			m_pulseClient = new PulseClient();
			m_pulseClient.SetServerParams(ThumpSettings.GetServerIp(), ThumpSettings.GetServerPort(), ThumpSettings.GetUsername(), ThumpSettings.GetPassword(), ThumpSettings.GetAuthType(), true);


			string cacheRoot = FileSystem.CacheDirectory;
			string databasePath = Path.Combine(cacheRoot, "thump.db");
			string blobDirectory = Path.Combine(cacheRoot, "blobs");
			m_cache = new ThumpCache(databasePath, blobDirectory);
			m_data = new ThumpData(m_pulseClient, m_cache);

#if ANDROID
			m_player = new AndroidThumpPlayer(this, m_data);
#else
			m_player = new StubThumpPlayer();
#endif

			m_shuffleEnabled = ThumpSettings.GetShuffleEnabled();
			m_repeatMode = ThumpSettings.GetRepeatMode();
			m_player.SetShuffleEnabled(m_shuffleEnabled);
			m_player.SetRepeatMode(m_repeatMode);

			m_homeView = new HomeView(this);
			m_libraryView = new LibraryView(this);
			m_searchView = new SearchView(this);
			m_settingsView = new SettingsView(this);

			m_miniPlayer = new MiniPlayer(this);
			Grid.SetRow(m_miniPlayer, 1);
			m_miniPlayer.IsVisible = false;
			m_rootGrid.Children.Add(m_miniPlayer);

			m_navFooter = new NavFooter(this);
			Grid.SetRow(m_navFooter, 2);
			m_rootGrid.Children.Add(m_navFooter);

			m_homeView.Initialize();
			m_libraryView.Initialize();
			m_searchView.Initialize();
			m_settingsView.Initialize();
			m_miniPlayer.Initialize();
			m_navFooter.Initialize();

			NavigateToHome();
		}

		public ThumpCache GetCache()
		{
			return m_cache;
		}
		public void NavigateToHome()
		{
			m_activeTab = eTab.Home;
			m_detailStack.Clear();
			m_contentHost.Content = m_homeView;
			m_navFooter.SetActiveTab(eTab.Home);
			RestoreMiniPlayerIfActive();
		}

		public void NavigateToLibrary()
		{
			m_activeTab = eTab.Library;
			m_detailStack.Clear();
			m_contentHost.Content = m_libraryView;
			m_navFooter.SetActiveTab(eTab.Library);
			RestoreMiniPlayerIfActive();
		}

		public void NavigateToSearch()
		{
			m_activeTab = eTab.Search;
			m_detailStack.Clear();
			m_contentHost.Content = m_searchView;
			m_navFooter.SetActiveTab(eTab.Search);
			RestoreMiniPlayerIfActive();
		}

		public void NavigateToSettings()
		{
			m_activeTab = eTab.Settings;
			m_detailStack.Clear();
			m_contentHost.Content = m_settingsView;
			m_navFooter.SetActiveTab(eTab.Settings);
			RestoreMiniPlayerIfActive();
		}

		private void PushDetail(View detail)
		{
			m_detailStack.Add(detail);
			m_contentHost.Content = detail;
		}

		public void OnBackPressed()
		{
			if (m_detailStack.Count == 0)
			{
				return;
			}
			m_detailStack.RemoveAt(m_detailStack.Count - 1);
			if (m_detailStack.Count > 0)
			{
				m_contentHost.Content = m_detailStack[m_detailStack.Count - 1];
				if (m_contentHost.Content != m_nowPlayingView)
				{
					RestoreMiniPlayerIfActive();
				}
				return;
			}
			if (m_activeTab == eTab.Home)
			{
				m_contentHost.Content = m_homeView;
			}
			else if (m_activeTab == eTab.Library)
			{
				m_contentHost.Content = m_libraryView;
			}
			else if (m_activeTab == eTab.Search)
			{
				m_contentHost.Content = m_searchView;
			}
			else if (m_activeTab == eTab.Settings)
			{
				m_contentHost.Content = m_settingsView;
			}
			if (m_contentHost.Content != m_nowPlayingView)
			{
				RestoreMiniPlayerIfActive();
			}
		}

		protected override bool OnBackButtonPressed()
		{
			if (m_detailStack.Count > 0)
			{
				OnBackPressed();
				return true;
			}
			if (m_activeTab != eTab.Home)
			{
				NavigateToHome();
				return true;
			}
			return base.OnBackButtonPressed();
		}

		public void OnArtistSelected(PulseArtist artist)
		{
			ArtistDetailView detail = new ArtistDetailView(this, artist);
			detail.Initialize();
			PushDetail(detail);
		}

		public void OnAlbumSelected(PulseAlbum album)
		{
			AlbumDetailView detail = new AlbumDetailView(this, album);
			detail.Initialize();
			PushDetail(detail);
		}

		public void OnPlaylistSelected(PulsePlaylist playlist)
		{
			PlaylistDetailView detail = new PlaylistDetailView(this, playlist);
			detail.Initialize();
			PushDetail(detail);
		}

		public void OnGenreSelected(PulseGenre genre)
		{
			GenreDetailView detail = new GenreDetailView(this, genre);
			detail.Initialize();
			PushDetail(detail);
		}

		public void OnHomeItemSelected(ThumpDataOb item)
		{
			if (item.Kind == eDataType.Album)
			{
				PulseAlbum album = item as PulseAlbum;
				if (album != null)
				{
					OnAlbumSelected(album);
				}
			}
			else if (item.Kind == eDataType.Playlist)
			{
				PulsePlaylist playlist = item as PulsePlaylist;
				if (playlist != null)
				{
					OnPlaylistSelected(playlist);
				}
			}
			else if (item.Kind == eDataType.Artist)
			{
				PulseArtist artist = item as PulseArtist;
				if (artist != null)
				{
					OnArtistSelected(artist);
				}
			}
			else if (item.Kind == eDataType.Track)
			{
				PulseTrack song = item as PulseTrack;
				if (song != null)
				{
					List<PulseTrack> oneShotQueue = new List<PulseTrack>();
					oneShotQueue.Add(song);
					OnPlayTracks(oneShotQueue, 0);
				}
			}
		}

		public void OnTrackSelected(PulseTrack song)
		{
			List<PulseTrack> oneShotQueue = new List<PulseTrack>();
			oneShotQueue.Add(song);
			OnPlayTracks(oneShotQueue, 0);
		}

		public void OnPlayTracks(List<PulseTrack> tracks, int startIndex)
		{
			if (tracks == null || tracks.Count == 0)
			{
				return;
			}
			int clampedIndex = startIndex;
			if (clampedIndex < 0 || clampedIndex >= tracks.Count)
			{
				clampedIndex = 0;
			}
			m_currentQueue = new List<PulseTrack>(tracks);
			m_currentQueueIndex = clampedIndex;
			m_currentTrack = m_currentQueue[clampedIndex];
			m_miniPlayer.SetTrack(m_currentTrack);
			ShowMiniPlayer();
			m_player.Play(m_currentQueue, clampedIndex);
		}

		public void OnPlayTracksShuffled(List<PulseTrack> tracks)
		{
			if (tracks == null || tracks.Count == 0)
			{
				return;
			}
			SetShuffleState(true);
			OnPlayTracks(tracks, 0);
		}

		public void OnTogglePlayPause()
		{
			if (m_playbackState == ePlaybackState.Playing || m_playbackState == ePlaybackState.Buffering)
			{
				m_player.Pause();
			}
			else
			{
				m_player.Resume();
			}
		}

		public void OnNext()
		{
			m_player.Next();
		}

		public void OnPrevious()
		{
			m_player.Previous();
		}

		public void OnSeekToFraction(double fraction)
		{
			long position = (long)(fraction * m_currentDurationMs);
			m_player.SeekTo(position);
		}

		public void OnToggleShuffle()
		{
			SetShuffleState(!m_shuffleEnabled);
		}

		private void SetShuffleState(bool enabled)
		{
			m_shuffleEnabled = enabled;
			ThumpSettings.SetShuffleEnabled(enabled);
			m_player.SetShuffleEnabled(enabled);
			if (m_nowPlayingView != null)
			{
				m_nowPlayingView.SetShuffleState(enabled);
			}
		}

		public void OnCycleRepeat()
		{
			eRepeatMode next;
			if (m_repeatMode == eRepeatMode.Off)
			{
				next = eRepeatMode.All;
			}
			else if (m_repeatMode == eRepeatMode.All)
			{
				next = eRepeatMode.One;
			}
			else
			{
				next = eRepeatMode.Off;
			}
			SetRepeatState(next);
		}

		private void SetRepeatState(eRepeatMode mode)
		{
			m_repeatMode = mode;
			ThumpSettings.SetRepeatMode(mode);
			m_player.SetRepeatMode(mode);
			if (m_nowPlayingView != null)
			{
				m_nowPlayingView.SetRepeatState(mode);
			}
		}

		public bool GetShuffleEnabled()
		{
			return m_shuffleEnabled;
		}

		public eRepeatMode GetRepeatMode()
		{
			return m_repeatMode;
		}

		public void OnAddToQueue(List<PulseTrack> tracks)
		{
			if (tracks == null || tracks.Count == 0)
			{
				return;
			}
			if (m_currentQueue.Count == 0)
			{
				OnPlayTracks(tracks, 0);
				return;
			}
			m_currentQueue.AddRange(tracks);
			m_player.AddToQueue(tracks);
			if (m_nowPlayingView != null)
			{
				m_nowPlayingView.RefreshQueue();
			}
		}

		public void OnPlayNext(List<PulseTrack> tracks)
		{
			if (tracks == null || tracks.Count == 0)
			{
				return;
			}
			if (m_currentQueue.Count == 0)
			{
				OnPlayTracks(tracks, 0);
				return;
			}
			int insertAt = m_currentQueueIndex + 1;
			if (insertAt > m_currentQueue.Count)
			{
				insertAt = m_currentQueue.Count;
			}
			m_currentQueue.InsertRange(insertAt, tracks);
			m_player.PlayNext(tracks);
			if (m_nowPlayingView != null)
			{
				m_nowPlayingView.RefreshQueue();
			}
		}

		public void OnSeekToQueueItem(int index)
		{
			if (index < 0 || index >= m_currentQueue.Count)
			{
				return;
			}
			m_currentQueueIndex = index;
			m_currentTrack = m_currentQueue[index];
			m_miniPlayer.SetTrack(m_currentTrack);
			ShowMiniPlayer();
			if (m_nowPlayingView != null)
			{
				m_nowPlayingView.SetTrack(m_currentTrack);
			}
			m_player.SeekToQueueItem(index);
		}

		public List<PulseTrack> GetQueue()
		{
			return m_currentQueue;
		}

		public int GetQueueIndex()
		{
			return m_currentQueueIndex;
		}

		public void OnQueueTrackSelected(PulseTrack track)
		{
			int index = m_currentQueue.IndexOf(track);
			if (index < 0)
			{
				return;
			}
			OnSeekToQueueItem(index);
		}

		public async void OnTrackOptions(PulseTrack track)
		{
			if (track == null)
			{
				return;
			}
			string playNext = "Play Next";
			string addToQueue = "Add to Queue";
			string choice = await DisplayActionSheet(track.Title, "Cancel", null, playNext, addToQueue);
			List<PulseTrack> single = new List<PulseTrack>();
			single.Add(track);
			if (choice == playNext)
			{
				OnPlayNext(single);
			}
			else if (choice == addToQueue)
			{
				OnAddToQueue(single);
			}
		}

		public void OnPlaybackStateChanged(ePlaybackState state)
		{
			m_playbackState = state;
			bool playing = state == ePlaybackState.Playing;
			m_miniPlayer.SetPlaying(playing);
			if (m_nowPlayingView != null)
			{
				m_nowPlayingView.SetPlaying(playing);
			}
		}

		public void OnPlaybackPositionChanged(long positionMilliseconds, long durationMilliseconds)
		{
			m_currentDurationMs = durationMilliseconds;
			double fraction = 0;
			if (durationMilliseconds > 0)
			{
				fraction = (double)positionMilliseconds / (double)durationMilliseconds;
			}
			m_miniPlayer.SetProgress(fraction);
			if (m_nowPlayingView != null)
			{
				m_nowPlayingView.UpdatePosition(positionMilliseconds, durationMilliseconds);
			}
		}

		public void OnCurrentTrackChanged(PulseTrack track)
		{
			if (track == null)
			{
				return;
			}
			m_currentTrack = track;
			int foundIndex = m_currentQueue.IndexOf(track);
			if (foundIndex >= 0)
			{
				m_currentQueueIndex = foundIndex;
			}
			m_miniPlayer.SetTrack(track);
			ShowMiniPlayer();
			if (m_nowPlayingView != null)
			{
				m_nowPlayingView.SetTrack(track);
			}
		}

		public void OnTrackEnded()
		{
			m_playbackState = ePlaybackState.Ended;
			m_miniPlayer.SetPlaying(false);
			if (m_nowPlayingView != null)
			{
				m_nowPlayingView.SetPlaying(false);
			}
		}

		public void OnPlayArtist(PulseArtist artist, bool shuffle)
		{
			if (artist == null)
			{
				return;
			}
			bool started = false;
			m_data.GetAlbumsForArtist(artist, (albums) =>
			{
				// The data route can fire its callback more than once (cache fast-path
				// then network); start the walk only once.
				if (started)
				{
					return;
				}
				started = true;
				if (albums == null || albums.Count == 0)
				{
					return;
				}
				List<PulseTrack> combined = new List<PulseTrack>();
				AccumulateArtistTracks(albums, 0, combined, shuffle);
			});
		}

		private void AccumulateArtistTracks(List<PulseAlbum> albums, int index, List<PulseTrack> combined, bool shuffle)
		{
			if (index >= albums.Count)
			{
				if (combined.Count == 0)
				{
					return;
				}
				if (shuffle)
				{
					OnPlayTracksShuffled(combined);
				}
				else
				{
					OnPlayTracks(combined, 0);
				}
				return;
			}
			bool advanced = false;
			m_data.GetTracksForAlbum(albums[index], (tracks) =>
			{
				// Guard against the route's double callback: advancing twice here would
				// branch the recursion and flood requests / duplicate the queue.
				if (advanced)
				{
					return;
				}
				advanced = true;
				if (tracks != null)
				{
					combined.AddRange(tracks);
				}
				AccumulateArtistTracks(albums, index + 1, combined, shuffle);
			});
		}

		public PulseTrack GetCurrentTrack()
		{
			return m_currentTrack;
		}

		public void OpenNowPlaying()
		{
			if (m_nowPlayingView == null)
			{
				m_nowPlayingView = new NowPlayingView(this);
				m_nowPlayingView.Initialize();
			}
			if (m_currentTrack != null)
			{
				m_nowPlayingView.SetTrack(m_currentTrack);
			}
			m_nowPlayingView.SetPlaying(m_playbackState == ePlaybackState.Playing);
			PushDetail(m_nowPlayingView);
			HideMiniPlayer();
		}

		public void ShowMiniPlayer()
		{
			m_miniPlayer.IsVisible = true;
		}

		public void HideMiniPlayer()
		{
			m_miniPlayer.IsVisible = false;
		}

		private void RestoreMiniPlayerIfActive()
		{
			if (m_currentTrack == null)
			{
				return;
			}
			ShowMiniPlayer();
		}
	}
}

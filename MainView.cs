using System.Collections.Generic;
using System.IO;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Storage;
using Thump.Data;
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
		private bool m_inDetailView;

		private List<PulseTrack> m_currentQueue = new List<PulseTrack>();
		private int m_currentQueueIndex;
		private PulseTrack m_currentTrack;
		private ThumpData m_data;

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
			m_pulseClient.SetServerParams("192.168.5.5","32458", "Rob", "asdf", PulseClient.eSubSonicAuthType.Token, true);

			string cacheRoot = FileSystem.CacheDirectory;
			string databasePath = Path.Combine(cacheRoot, "thump.db");
			string blobDirectory = Path.Combine(cacheRoot, "blobs");
			m_cache = new ThumpCache(databasePath, blobDirectory);
			m_data = new ThumpData(m_pulseClient, m_cache);

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
			m_inDetailView = false;
			m_contentHost.Content = m_homeView;
			m_navFooter.SetActiveTab(eTab.Home);
		}

		public void NavigateToLibrary()
		{
			m_activeTab = eTab.Library;
			m_inDetailView = false;
			m_contentHost.Content = m_libraryView;
			m_navFooter.SetActiveTab(eTab.Library);
		}

		public void NavigateToSearch()
		{
			m_activeTab = eTab.Search;
			m_inDetailView = false;
			m_contentHost.Content = m_searchView;
			m_navFooter.SetActiveTab(eTab.Search);
		}

		public void NavigateToSettings()
		{
			m_activeTab = eTab.Settings;
			m_inDetailView = false;
			m_contentHost.Content = m_settingsView;
			m_navFooter.SetActiveTab(eTab.Settings);
		}

		private void PushDetail(View detail)
		{
			m_inDetailView = true;
			m_contentHost.Content = detail;
		}

		public void OnBackPressed()
		{
			if (!m_inDetailView)
			{
				return;
			}
			m_inDetailView = false;
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
			m_currentQueue = tracks;
			m_currentQueueIndex = startIndex;
			m_currentTrack = tracks[startIndex];
			m_miniPlayer.SetTrack(m_currentTrack);
			ShowMiniPlayer();
		}

		public void OnPlayArtist(PulseArtist artist, bool shuffle)
		{
			// TODO: real impl walks albums and concatenates tracks via async callbacks.
			// For prototype, queue a single placeholder track tagged with the artist name
			// so the mini-player reacts to the action.
			PulseTrack stub = new PulseTrack();
			stub.Title = artist.Name;
			stub.Artist = "Various albums";
			stub.Duration = 240;
			List<PulseTrack> queue = new List<PulseTrack>();
			queue.Add(stub);
			OnPlayTracks(queue, 0);
		}

		public PulseTrack GetCurrentTrack()
		{
			return m_currentTrack;
		}

		public void OpenNowPlaying()
		{
			NowPlayingView nowPlaying = new NowPlayingView(this);
			nowPlaying.Initialize();
			if (m_currentTrack != null)
			{
				nowPlaying.SetTrack(m_currentTrack);
			}
			PushDetail(nowPlaying);
		}

		public void ShowMiniPlayer()
		{
			m_miniPlayer.IsVisible = true;
		}

		public void HideMiniPlayer()
		{
			m_miniPlayer.IsVisible = false;
		}
	}
}

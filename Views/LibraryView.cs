using System;
using System.Collections.Generic;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Pulse;
using Thump.Views.Tiles;

namespace Thump.Views
{
	public enum eLibraryChip
	{
		Artists,
		Albums,
		Playlists,
		Genres,
	}

	public class LibraryView : ThumpView
	{
		private static readonly Color s_chipActiveBackground = Color.FromArgb("#3b82f6");
		private static readonly Color s_chipActiveText = Color.FromArgb("#0a0a0c");
		private static readonly Color s_chipInactiveBackground = Color.FromArgb("#101014");
		private static readonly Color s_chipInactiveText = Color.FromArgb("#e8e8ec");

		private eLibraryChip m_activeChip = eLibraryChip.Artists;

		private Button m_chipArtists;
		private Button m_chipAlbums;
		private Button m_chipPlaylists;
		private Button m_chipGenres;
		private CollectionView m_artistsList;
		private CollectionView m_albumsList;
		private CollectionView m_playlistsList;
		private CollectionView m_genresList;

		public LibraryView(MainView mainView) : base(mainView)
		{
			
		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Background;

			Grid grid = new Grid();

			RowDefinition titleRow = new RowDefinition();
			titleRow.Height = GridLength.Auto;
			RowDefinition chipRow = new RowDefinition();
			chipRow.Height = GridLength.Auto;
			RowDefinition listRow = new RowDefinition();
			listRow.Height = GridLength.Star;
			grid.RowDefinitions.Add(titleRow);
			grid.RowDefinitions.Add(chipRow);
			grid.RowDefinitions.Add(listRow);

			grid.Children.Add(BuildTitle());
			grid.Children.Add(BuildChips());
			grid.Children.Add(BuildLists());

			Content = grid;
		}

		private View BuildTitle()
		{
			Label header = new Label();
			header.Text = "Library";
			header.FontSize = 24;
			header.TextColor = ThumpColors.OnBackground;
			header.Padding = new Thickness(16, 12);

			Grid.SetRow(header, 0);
			return header;
		}

		private View BuildChips()
		{
			HorizontalStackLayout chipStack = new HorizontalStackLayout();
			chipStack.Spacing = 8;
			chipStack.Padding = new Thickness(16, 0, 16, 12);

			m_chipArtists = new Button();
			m_chipArtists.Text = "Artists";
			m_chipArtists.TextColor = ThumpColors.Background;
			m_chipArtists.BackgroundColor = ThumpColors.Accent;
			m_chipArtists.CornerRadius = 16;
			m_chipArtists.FontSize = 13;
			m_chipArtists.Padding = new Thickness(14, 4);
			m_chipArtists.HeightRequest = 32;
			m_chipArtists.Clicked += OnChipArtistsClicked;
			chipStack.Children.Add(m_chipArtists);

			m_chipAlbums = new Button();
			m_chipAlbums.Text = "Albums";
			m_chipAlbums.TextColor = ThumpColors.OnBackground;
			m_chipAlbums.BackgroundColor = ThumpColors.Surface;
			m_chipAlbums.CornerRadius = 16;
			m_chipAlbums.FontSize = 13;
			m_chipAlbums.Padding = new Thickness(14, 4);
			m_chipAlbums.HeightRequest = 32;
			m_chipAlbums.Clicked += OnChipAlbumsClicked;
			chipStack.Children.Add(m_chipAlbums);

			m_chipPlaylists = new Button();
			m_chipPlaylists.Text = "Playlists";
			m_chipPlaylists.TextColor = ThumpColors.OnBackground;
			m_chipPlaylists.BackgroundColor = ThumpColors.Surface;
			m_chipPlaylists.CornerRadius = 16;
			m_chipPlaylists.FontSize = 13;
			m_chipPlaylists.Padding = new Thickness(14, 4);
			m_chipPlaylists.HeightRequest = 32;
			m_chipPlaylists.Clicked += OnChipPlaylistsClicked;
			chipStack.Children.Add(m_chipPlaylists);

			m_chipGenres = new Button();
			m_chipGenres.Text = "Genres";
			m_chipGenres.TextColor = ThumpColors.OnBackground;
			m_chipGenres.BackgroundColor = ThumpColors.Surface;
			m_chipGenres.CornerRadius = 16;
			m_chipGenres.FontSize = 13;
			m_chipGenres.Padding = new Thickness(14, 4);
			m_chipGenres.HeightRequest = 32;
			m_chipGenres.Clicked += OnChipGenresClicked;
			chipStack.Children.Add(m_chipGenres);

			Grid.SetRow(chipStack, 1);
			return chipStack;
		}

		private View BuildLists()
		{
			Grid listContainer = new Grid();

			m_artistsList = new CollectionView();
			m_artistsList.IsVisible = true;
			m_artistsList.BackgroundColor = ThumpColors.Background;
			m_artistsList.ItemTemplate = new DataTemplate(typeof(ArtistRowTile));
			listContainer.Children.Add(m_artistsList);

			m_albumsList = new CollectionView();
			m_albumsList.IsVisible = false;
			m_albumsList.BackgroundColor = ThumpColors.Background;
			m_albumsList.ItemTemplate = new DataTemplate(typeof(AlbumRowTile));
			listContainer.Children.Add(m_albumsList);

			m_playlistsList = new CollectionView();
			m_playlistsList.IsVisible = false;
			m_playlistsList.BackgroundColor = ThumpColors.Background;
			m_playlistsList.ItemTemplate = new DataTemplate(typeof(PlaylistRowTile));
			listContainer.Children.Add(m_playlistsList);

			m_genresList = new CollectionView();
			m_genresList.IsVisible = false;
			m_genresList.BackgroundColor = ThumpColors.Background;
			m_genresList.ItemTemplate = new DataTemplate(typeof(GenreRowTile));
			listContainer.Children.Add(m_genresList);

			Grid.SetRow(listContainer, 2);
			return listContainer;
		}

		public override void Initialize()
		{
			base.Initialize();
			MainView.Data.GetArtists(OnArtistsLoaded);
			MainView.Data.GetAlbums(OnAlbumsLoaded);
			MainView.Data.GetPlaylists(OnPlaylistsLoaded);
			MainView.Data.GetGenres(OnGenresLoaded);
			SetActiveChip(eLibraryChip.Artists);
		}

		private void OnArtistsLoaded(List<PulseArtist> artists)
		{
			m_artistsList.ItemsSource = artists;
		}

		private void OnAlbumsLoaded(List<PulseAlbum> albums)
		{
			m_albumsList.ItemsSource = albums;
		}

		private void OnPlaylistsLoaded(List<PulsePlaylist> playlists)
		{
			m_playlistsList.ItemsSource = playlists;
		}

		private void OnGenresLoaded(List<PulseGenre> genres)
		{
			m_genresList.ItemsSource = genres;
		}

		private void SetActiveChip(eLibraryChip chip)
		{
			m_activeChip = chip;

			m_chipArtists.BackgroundColor = s_chipInactiveBackground;
			m_chipArtists.TextColor = s_chipInactiveText;
			m_chipAlbums.BackgroundColor = s_chipInactiveBackground;
			m_chipAlbums.TextColor = s_chipInactiveText;
			m_chipPlaylists.BackgroundColor = s_chipInactiveBackground;
			m_chipPlaylists.TextColor = s_chipInactiveText;
			m_chipGenres.BackgroundColor = s_chipInactiveBackground;
			m_chipGenres.TextColor = s_chipInactiveText;

			m_artistsList.IsVisible = false;
			m_albumsList.IsVisible = false;
			m_playlistsList.IsVisible = false;
			m_genresList.IsVisible = false;

			if (chip == eLibraryChip.Artists)
			{
				m_chipArtists.BackgroundColor = s_chipActiveBackground;
				m_chipArtists.TextColor = s_chipActiveText;
				m_artistsList.IsVisible = true;
			}
			else if (chip == eLibraryChip.Albums)
			{
				m_chipAlbums.BackgroundColor = s_chipActiveBackground;
				m_chipAlbums.TextColor = s_chipActiveText;
				m_albumsList.IsVisible = true;
			}
			else if (chip == eLibraryChip.Playlists)
			{
				m_chipPlaylists.BackgroundColor = s_chipActiveBackground;
				m_chipPlaylists.TextColor = s_chipActiveText;
				m_playlistsList.IsVisible = true;
			}
			else if (chip == eLibraryChip.Genres)
			{
				m_chipGenres.BackgroundColor = s_chipActiveBackground;
				m_chipGenres.TextColor = s_chipActiveText;
				m_genresList.IsVisible = true;
			}
		}

		private void OnChipArtistsClicked(object sender, EventArgs e)
		{
			SetActiveChip(eLibraryChip.Artists);
		}

		private void OnChipAlbumsClicked(object sender, EventArgs e)
		{
			SetActiveChip(eLibraryChip.Albums);
		}

		private void OnChipPlaylistsClicked(object sender, EventArgs e)
		{
			SetActiveChip(eLibraryChip.Playlists);
		}

		private void OnChipGenresClicked(object sender, EventArgs e)
		{
			SetActiveChip(eLibraryChip.Genres);
		}
	}
}

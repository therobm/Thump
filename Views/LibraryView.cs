using System;
using System.Collections.Generic;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Controls.Shapes;
using Microsoft.Maui.Graphics;
using Thump.Pulse;
using Thump.Views.Tiles;

namespace Thump.Views
{
	public enum eLibraryButton
	{
		Artists,
		Albums,
		Playlists,
		Genres,
	}

	public enum eLibrarySort
	{
		Alphabetical,
		DateAdded,
		DateReleased,
	}

	public enum eLibraryLayout
	{
		List,
		Grid,
	}

	public class LibraryView : ThumpView
	{
		private const int s_gridSpan = 3;

		private static readonly Color s_buttonActiveBackground = Color.FromArgb("#3b82f6");
		private static readonly Color s_buttonActiveText = Color.FromArgb("#0a0a0c");
		private static readonly Color s_buttonInactiveBackground = Color.FromArgb("#101014");
		private static readonly Color s_buttonInactiveText = Color.FromArgb("#e8e8ec");

		private eLibraryButton m_activeButton = eLibraryButton.Artists;
		private eLibrarySort m_activeSort = eLibrarySort.Alphabetical;
		private eLibraryLayout m_activeLayout = eLibraryLayout.List;

		private Button m_buttonArtists;
		private Button m_buttonAlbums;
		private Button m_buttonPlaylists;
		private Button m_buttonGenres;

		private Button m_sortAlphabetical;
		private Button m_sortDateAdded;
		private Button m_sortDateReleased;
		private Button m_layoutToggle;
		private Button m_jumpButton;

		private CollectionView m_artistsList;
		private CollectionView m_albumsList;
		private CollectionView m_playlistsList;
		private CollectionView m_genresList;
		private Grid m_letterOverlay;

		private List<PulseArtist> m_artists;
		private List<PulseAlbum> m_albums;
		private List<PulsePlaylist> m_playlists;
		private List<PulseGenre> m_genres;

		public LibraryView(MainView mainView) : base(mainView)
		{

		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Background;

			Grid grid = new Grid();

			RowDefinition titleRow = new RowDefinition();
			titleRow.Height = GridLength.Auto;
			RowDefinition buttonRow = new RowDefinition();
			buttonRow.Height = GridLength.Auto;
			RowDefinition sortRow = new RowDefinition();
			sortRow.Height = GridLength.Auto;
			RowDefinition listRow = new RowDefinition();
			listRow.Height = GridLength.Star;
			grid.RowDefinitions.Add(titleRow);
			grid.RowDefinitions.Add(buttonRow);
			grid.RowDefinitions.Add(sortRow);
			grid.RowDefinitions.Add(listRow);

			grid.Children.Add(BuildTitle());
			grid.Children.Add(BuildButtons());
			grid.Children.Add(BuildSortRow());
			grid.Children.Add(BuildListArea());

			View overlay = BuildLetterOverlay();
			Grid.SetRow(overlay, 0);
			Grid.SetRowSpan(overlay, 4);
			grid.Children.Add(overlay);

			Content = grid;
		}

		private View BuildTitle()
		{
			Label header = new Label();
			header.Text = "Library";
			header.FontSize = 24;
			header.FontAttributes = FontAttributes.Bold;
			header.TextColor = ThumpColors.OnBackground;
			header.Padding = new Thickness(16, 12);

			Grid.SetRow(header, 0);
			return header;
		}

		private View BuildButtons()
		{
			HorizontalStackLayout buttonStack = new HorizontalStackLayout();
			buttonStack.Spacing = 8;
			buttonStack.Padding = new Thickness(16, 0, 16, 12);

			m_buttonArtists = BuildFilterButton("Artists");
			m_buttonArtists.Clicked += OnButtonArtistsClicked;
			buttonStack.Children.Add(m_buttonArtists);

			m_buttonAlbums = BuildFilterButton("Albums");
			m_buttonAlbums.Clicked += OnButtonAlbumsClicked;
			buttonStack.Children.Add(m_buttonAlbums);

			m_buttonPlaylists = BuildFilterButton("Playlists");
			m_buttonPlaylists.Clicked += OnButtonPlaylistsClicked;
			buttonStack.Children.Add(m_buttonPlaylists);

			m_buttonGenres = BuildFilterButton("Genres");
			m_buttonGenres.Clicked += OnButtonGenresClicked;
			buttonStack.Children.Add(m_buttonGenres);

			Grid.SetRow(buttonStack, 1);
			return buttonStack;
		}

		private Button BuildFilterButton(string text)
		{
			Button button = new Button();
			button.Text = text;
			button.TextColor = s_buttonInactiveText;
			button.BackgroundColor = s_buttonInactiveBackground;
			button.CornerRadius = 16;
			button.FontSize = 13;
			button.Padding = new Thickness(14, 4);
			button.HeightRequest = 32;
			return button;
		}

		private View BuildSortRow()
		{
			Grid sortGrid = new Grid();
			sortGrid.Padding = new Thickness(16, 0, 16, 12);

			ColumnDefinition sortColumn = new ColumnDefinition();
			sortColumn.Width = GridLength.Star;
			ColumnDefinition toggleColumn = new ColumnDefinition();
			toggleColumn.Width = GridLength.Auto;
			sortGrid.ColumnDefinitions.Add(sortColumn);
			sortGrid.ColumnDefinitions.Add(toggleColumn);

			HorizontalStackLayout sortStack = new HorizontalStackLayout();
			sortStack.Spacing = 4;
			sortStack.VerticalOptions = LayoutOptions.Center;

			m_sortAlphabetical = BuildSortButton("A–Z");
			m_sortAlphabetical.Clicked += OnSortAlphabeticalClicked;
			sortStack.Children.Add(m_sortAlphabetical);

			m_sortDateReleased = BuildSortButton("Released");
			m_sortDateReleased.Clicked += OnSortDateReleasedClicked;
			sortStack.Children.Add(m_sortDateReleased);

			m_sortDateAdded = BuildSortButton("Added");
			m_sortDateAdded.Clicked += OnSortDateAddedClicked;
			// No "date added" field exists on the data model yet, so this sort stays disabled.
			m_sortDateAdded.IsEnabled = false;
			m_sortDateAdded.Opacity = 0.4;
			sortStack.Children.Add(m_sortDateAdded);

			Grid.SetColumn(sortStack, 0);
			sortGrid.Children.Add(sortStack);

			HorizontalStackLayout rightStack = new HorizontalStackLayout();
			rightStack.Spacing = 8;
			rightStack.VerticalOptions = LayoutOptions.Center;

			m_jumpButton = new Button();
			m_jumpButton.Text = "A–Z ▾";
			m_jumpButton.TextColor = s_buttonInactiveText;
			m_jumpButton.BackgroundColor = s_buttonInactiveBackground;
			m_jumpButton.CornerRadius = 16;
			m_jumpButton.FontSize = 13;
			m_jumpButton.Padding = new Thickness(14, 4);
			m_jumpButton.HeightRequest = 32;
			m_jumpButton.VerticalOptions = LayoutOptions.Center;
			m_jumpButton.Clicked += OnJumpButtonClicked;
			rightStack.Children.Add(m_jumpButton);

			m_layoutToggle = new Button();
			m_layoutToggle.Text = "Grid";
			m_layoutToggle.TextColor = s_buttonInactiveText;
			m_layoutToggle.BackgroundColor = s_buttonInactiveBackground;
			m_layoutToggle.CornerRadius = 16;
			m_layoutToggle.FontSize = 13;
			m_layoutToggle.Padding = new Thickness(14, 4);
			m_layoutToggle.HeightRequest = 32;
			m_layoutToggle.VerticalOptions = LayoutOptions.Center;
			m_layoutToggle.Clicked += OnLayoutToggleClicked;
			rightStack.Children.Add(m_layoutToggle);

			Grid.SetColumn(rightStack, 1);
			sortGrid.Children.Add(rightStack);

			Grid.SetRow(sortGrid, 2);
			return sortGrid;
		}

		private Button BuildSortButton(string text)
		{
			Button button = new Button();
			button.Text = text;
			button.TextColor = ThumpColors.TextSecondary;
			button.BackgroundColor = Colors.Transparent;
			button.FontSize = 13;
			button.Padding = new Thickness(8, 2);
			button.HeightRequest = 32;
			return button;
		}

		private View BuildListArea()
		{
			View lists = BuildLists();
			Grid.SetRow(lists, 3);
			return lists;
		}

		private View BuildLists()
		{
			Grid listContainer = new Grid();

			m_artistsList = new CollectionView();
			m_artistsList.IsVisible = true;
			m_artistsList.BackgroundColor = ThumpColors.Background;
			listContainer.Children.Add(m_artistsList);

			m_albumsList = new CollectionView();
			m_albumsList.IsVisible = false;
			m_albumsList.BackgroundColor = ThumpColors.Background;
			listContainer.Children.Add(m_albumsList);

			m_playlistsList = new CollectionView();
			m_playlistsList.IsVisible = false;
			m_playlistsList.BackgroundColor = ThumpColors.Background;
			listContainer.Children.Add(m_playlistsList);

			m_genresList = new CollectionView();
			m_genresList.IsVisible = false;
			m_genresList.BackgroundColor = ThumpColors.Background;
			listContainer.Children.Add(m_genresList);

			return listContainer;
		}

		private View BuildLetterOverlay()
		{
			m_letterOverlay = new Grid();
			m_letterOverlay.BackgroundColor = Color.FromArgb("#CC000000");
			m_letterOverlay.IsVisible = false;

			TapGestureRecognizer backdropTap = new TapGestureRecognizer();
			backdropTap.Tapped += OnLetterOverlayBackdropTapped;
			m_letterOverlay.GestureRecognizers.Add(backdropTap);

			Border panel = new Border();
			panel.BackgroundColor = ThumpColors.Surface;
			panel.Stroke = new SolidColorBrush(ThumpColors.Divider);
			panel.StrokeThickness = 1;
			panel.Padding = new Thickness(16);
			panel.HorizontalOptions = LayoutOptions.Center;
			panel.VerticalOptions = LayoutOptions.Center;

			RoundRectangle panelShape = new RoundRectangle();
			panelShape.CornerRadius = new CornerRadius(16);
			panel.StrokeShape = panelShape;

			// Swallow taps on the panel so they don't reach the dismiss backdrop.
			TapGestureRecognizer panelTap = new TapGestureRecognizer();
			panelTap.Tapped += OnLetterPanelTapped;
			panel.GestureRecognizers.Add(panelTap);

			panel.Content = BuildLetterGrid();
			m_letterOverlay.Children.Add(panel);

			return m_letterOverlay;
		}

		private View BuildLetterGrid()
		{
			Grid letterGrid = new Grid();
			letterGrid.RowSpacing = 6;
			letterGrid.ColumnSpacing = 6;

			int columns = 6;
			for (int column = 0; column < columns; column++)
			{
				ColumnDefinition columnDefinition = new ColumnDefinition();
				columnDefinition.Width = GridLength.Auto;
				letterGrid.ColumnDefinitions.Add(columnDefinition);
			}

			int totalLetters = 26;
			int rows = (totalLetters + columns - 1) / columns;
			for (int row = 0; row < rows; row++)
			{
				RowDefinition rowDefinition = new RowDefinition();
				rowDefinition.Height = GridLength.Auto;
				letterGrid.RowDefinitions.Add(rowDefinition);
			}

			for (int index = 0; index < totalLetters; index++)
			{
				char letter = (char)('A' + index);
				Button letterButton = new Button();
				letterButton.Text = letter.ToString();
				letterButton.FontSize = 16;
				letterButton.FontFamily = "PoppinsSemiBold";
				letterButton.TextColor = ThumpColors.OnBackground;
				letterButton.BackgroundColor = ThumpColors.SurfaceElevated;
				letterButton.CornerRadius = 8;
				letterButton.Padding = new Thickness(0);
				letterButton.WidthRequest = 44;
				letterButton.HeightRequest = 44;
				letterButton.Clicked += OnLetterButtonClicked;

				Grid.SetRow(letterButton, index / columns);
				Grid.SetColumn(letterButton, index % columns);
				letterGrid.Children.Add(letterButton);
			}

			return letterGrid;
		}

		public override void Initialize()
		{
			base.Initialize();
			ApplyLayout();
			SetActiveButton(eLibraryButton.Artists);
			SetActiveSort(eLibrarySort.Alphabetical);
			MainView.Data.GetArtists(OnArtistsLoaded);
			MainView.Data.GetAlbums(OnAlbumsLoaded);
			MainView.Data.GetPlaylists(OnPlaylistsLoaded);
			MainView.Data.GetGenres(OnGenresLoaded);
		}

		private void OnArtistsLoaded(List<PulseArtist> artists)
		{
			m_artists = artists;
			BindArtists();
		}

		private void OnAlbumsLoaded(List<PulseAlbum> albums)
		{
			m_albums = albums;
			BindAlbums();
		}

		private void OnPlaylistsLoaded(List<PulsePlaylist> playlists)
		{
			m_playlists = playlists;
			BindPlaylists();
		}

		private void OnGenresLoaded(List<PulseGenre> genres)
		{
			m_genres = genres;
			BindGenres();
		}

		private void BindArtists()
		{
			if (m_artists == null)
			{
				return;
			}
			m_artists.Sort(CompareArtistByName);
			m_artistsList.ItemsSource = new List<PulseArtist>(m_artists);
		}

		private void BindAlbums()
		{
			if (m_albums == null)
			{
				return;
			}
			if (m_activeSort == eLibrarySort.DateReleased)
			{
				m_albums.Sort(CompareAlbumByYear);
			}
			else
			{
				m_albums.Sort(CompareAlbumByName);
			}
			m_albumsList.ItemsSource = new List<PulseAlbum>(m_albums);
		}

		private void BindPlaylists()
		{
			if (m_playlists == null)
			{
				return;
			}
			m_playlists.Sort(ComparePlaylistByName);
			m_playlistsList.ItemsSource = new List<PulsePlaylist>(m_playlists);
		}

		private void BindGenres()
		{
			if (m_genres == null)
			{
				return;
			}
			m_genres.Sort(CompareGenreByName);
			m_genresList.ItemsSource = new List<PulseGenre>(m_genres);
		}

		private static int CompareArtistByName(PulseArtist first, PulseArtist second)
		{
			return string.Compare(first.Name, second.Name, StringComparison.OrdinalIgnoreCase);
		}

		private static int CompareAlbumByName(PulseAlbum first, PulseAlbum second)
		{
			return string.Compare(first.Name, second.Name, StringComparison.OrdinalIgnoreCase);
		}

		private static int CompareAlbumByYear(PulseAlbum first, PulseAlbum second)
		{
			if (first.Year != second.Year)
			{
				return second.Year - first.Year;
			}
			return string.Compare(first.Name, second.Name, StringComparison.OrdinalIgnoreCase);
		}

		private static int ComparePlaylistByName(PulsePlaylist first, PulsePlaylist second)
		{
			return string.Compare(first.Name, second.Name, StringComparison.OrdinalIgnoreCase);
		}

		private static int CompareGenreByName(PulseGenre first, PulseGenre second)
		{
			return string.Compare(first.Name, second.Name, StringComparison.OrdinalIgnoreCase);
		}

		private void SetActiveButton(eLibraryButton button)
		{
			m_activeButton = button;

			m_buttonArtists.BackgroundColor = s_buttonInactiveBackground;
			m_buttonArtists.TextColor = s_buttonInactiveText;
			m_buttonAlbums.BackgroundColor = s_buttonInactiveBackground;
			m_buttonAlbums.TextColor = s_buttonInactiveText;
			m_buttonPlaylists.BackgroundColor = s_buttonInactiveBackground;
			m_buttonPlaylists.TextColor = s_buttonInactiveText;
			m_buttonGenres.BackgroundColor = s_buttonInactiveBackground;
			m_buttonGenres.TextColor = s_buttonInactiveText;

			m_artistsList.IsVisible = false;
			m_albumsList.IsVisible = false;
			m_playlistsList.IsVisible = false;
			m_genresList.IsVisible = false;

			if (button == eLibraryButton.Artists)
			{
				m_buttonArtists.BackgroundColor = s_buttonActiveBackground;
				m_buttonArtists.TextColor = s_buttonActiveText;
				m_artistsList.IsVisible = true;
			}
			else if (button == eLibraryButton.Albums)
			{
				m_buttonAlbums.BackgroundColor = s_buttonActiveBackground;
				m_buttonAlbums.TextColor = s_buttonActiveText;
				m_albumsList.IsVisible = true;
			}
			else if (button == eLibraryButton.Playlists)
			{
				m_buttonPlaylists.BackgroundColor = s_buttonActiveBackground;
				m_buttonPlaylists.TextColor = s_buttonActiveText;
				m_playlistsList.IsVisible = true;
			}
			else if (button == eLibraryButton.Genres)
			{
				m_buttonGenres.BackgroundColor = s_buttonActiveBackground;
				m_buttonGenres.TextColor = s_buttonActiveText;
				m_genresList.IsVisible = true;
			}
		}

		private void SetActiveSort(eLibrarySort sort)
		{
			m_activeSort = sort;

			m_sortAlphabetical.TextColor = ThumpColors.TextSecondary;
			m_sortDateReleased.TextColor = ThumpColors.TextSecondary;
			m_sortDateAdded.TextColor = ThumpColors.TextSecondary;

			if (sort == eLibrarySort.Alphabetical)
			{
				m_sortAlphabetical.TextColor = ThumpColors.Accent;
			}
			else if (sort == eLibrarySort.DateReleased)
			{
				m_sortDateReleased.TextColor = ThumpColors.Accent;
			}
			else if (sort == eLibrarySort.DateAdded)
			{
				m_sortDateAdded.TextColor = ThumpColors.Accent;
			}

			m_jumpButton.IsVisible = sort == eLibrarySort.Alphabetical;

			BindArtists();
			BindAlbums();
			BindPlaylists();
			BindGenres();
		}

		private void ApplyLayout()
		{
			ApplyLayoutToList(m_artistsList, typeof(ArtistRowTile));
			ApplyLayoutToList(m_albumsList, typeof(AlbumRowTile));
			ApplyLayoutToList(m_playlistsList, typeof(PlaylistRowTile));
			ApplyLayoutToList(m_genresList, typeof(GenreRowTile));
		}

		private void ApplyLayoutToList(CollectionView list, Type rowTileType)
		{
			if (m_activeLayout == eLibraryLayout.Grid)
			{
				list.ItemsLayout = new GridItemsLayout(s_gridSpan, ItemsLayoutOrientation.Vertical);
				list.ItemTemplate = new DataTemplate(typeof(LibraryGridTile));
			}
			else
			{
				list.ItemsLayout = LinearItemsLayout.Vertical;
				list.ItemTemplate = new DataTemplate(rowTileType);
			}
		}

		private CollectionView GetVisibleList()
		{
			if (m_activeButton == eLibraryButton.Albums)
			{
				return m_albumsList;
			}
			if (m_activeButton == eLibraryButton.Playlists)
			{
				return m_playlistsList;
			}
			if (m_activeButton == eLibraryButton.Genres)
			{
				return m_genresList;
			}
			return m_artistsList;
		}

		private int FindFirstIndexForLetter(string letter)
		{
			List<string> names = GetVisibleNames();
			if (names == null)
			{
				return -1;
			}
			for (int index = 0; index < names.Count; index++)
			{
				string name = names[index];
				if (string.IsNullOrEmpty(name))
				{
					continue;
				}
				string firstLetter = name.Substring(0, 1).ToUpperInvariant();
				if (firstLetter == letter)
				{
					return index;
				}
			}
			return -1;
		}

		private List<string> GetVisibleNames()
		{
			List<string> names = new List<string>();
			if (m_activeButton == eLibraryButton.Albums)
			{
				if (m_albums == null)
				{
					return null;
				}
				for (int index = 0; index < m_albums.Count; index++)
				{
					names.Add(m_albums[index].Name);
				}
				return names;
			}
			if (m_activeButton == eLibraryButton.Playlists)
			{
				if (m_playlists == null)
				{
					return null;
				}
				for (int index = 0; index < m_playlists.Count; index++)
				{
					names.Add(m_playlists[index].Name);
				}
				return names;
			}
			if (m_activeButton == eLibraryButton.Genres)
			{
				if (m_genres == null)
				{
					return null;
				}
				for (int index = 0; index < m_genres.Count; index++)
				{
					names.Add(m_genres[index].Name);
				}
				return names;
			}
			if (m_artists == null)
			{
				return null;
			}
			for (int index = 0; index < m_artists.Count; index++)
			{
				names.Add(m_artists[index].Name);
			}
			return names;
		}

		private void OnJumpButtonClicked(object sender, EventArgs e)
		{
			m_letterOverlay.IsVisible = true;
		}

		private void OnLetterOverlayBackdropTapped(object sender, EventArgs e)
		{
			m_letterOverlay.IsVisible = false;
		}

		private void OnLetterPanelTapped(object sender, EventArgs e)
		{
		}

		private void OnLetterButtonClicked(object sender, EventArgs e)
		{
			Button button = sender as Button;
			if (button == null)
			{
				return;
			}
			m_letterOverlay.IsVisible = false;
			int index = FindFirstIndexForLetter(button.Text);
			if (index < 0)
			{
				return;
			}
			CollectionView list = GetVisibleList();
			list.ScrollTo(index, -1, ScrollToPosition.Start, true);
		}

		private void OnButtonArtistsClicked(object sender, EventArgs e)
		{
			SetActiveButton(eLibraryButton.Artists);
		}

		private void OnButtonAlbumsClicked(object sender, EventArgs e)
		{
			SetActiveButton(eLibraryButton.Albums);
		}

		private void OnButtonPlaylistsClicked(object sender, EventArgs e)
		{
			SetActiveButton(eLibraryButton.Playlists);
		}

		private void OnButtonGenresClicked(object sender, EventArgs e)
		{
			SetActiveButton(eLibraryButton.Genres);
		}

		private void OnSortAlphabeticalClicked(object sender, EventArgs e)
		{
			SetActiveSort(eLibrarySort.Alphabetical);
		}

		private void OnSortDateReleasedClicked(object sender, EventArgs e)
		{
			SetActiveSort(eLibrarySort.DateReleased);
		}

		private void OnSortDateAddedClicked(object sender, EventArgs e)
		{
			SetActiveSort(eLibrarySort.DateAdded);
		}

		private void OnLayoutToggleClicked(object sender, EventArgs e)
		{
			if (m_activeLayout == eLibraryLayout.List)
			{
				m_activeLayout = eLibraryLayout.Grid;
				m_layoutToggle.Text = "List";
			}
			else
			{
				m_activeLayout = eLibraryLayout.List;
				m_layoutToggle.Text = "Grid";
			}
			ApplyLayout();
		}
	}
}

using System.Collections.Generic;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Thump.Pulse;
using Thump.Views.Tiles;

namespace Thump.Views
{
	public class HomeView : ThumpView
	{
		private const int s_topItemCount = 8;
		private const int s_topItemColumns = 2;

		private HomeTopTile[] m_topTiles;
		private CollectionView m_recentlyPlayed;
		private CollectionView m_yourPlaylists;
		private CollectionView m_popularArtists;
		private CollectionView m_recentlyAdded;
		private CollectionView m_favorites;

		public HomeView(MainView mainView) : base(mainView)
		{

		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Background;

			StackLayout stack = new StackLayout();
			stack.Spacing = 20;
			stack.Padding = new Thickness(0, 12, 0, 12);

			stack.Children.Add(BuildTopPanel());
			m_recentlyPlayed = BuildShelf(stack, "Recently Played");
			m_yourPlaylists = BuildShelf(stack, "Your Playlists");
			m_popularArtists = BuildShelf(stack, "Popular Artists");
			m_recentlyAdded = BuildShelf(stack, "Recently Added");
			m_favorites = BuildShelf(stack, "Favorites");

			ScrollView scroll = new ScrollView();
			scroll.Content = stack;
			Content = scroll;
		}

		private View BuildTopPanel()
		{
			StackLayout section = new StackLayout();
			section.Spacing = 10;

			Label header = new Label();
			header.Text = "Top Picks";
			header.FontSize = 18;
			header.FontAttributes = FontAttributes.Bold;
			header.TextColor = ThumpColors.OnBackground;
			header.Padding = new Thickness(16, 0);
			section.Children.Add(header);

			Grid panelGrid = new Grid();
			panelGrid.Padding = new Thickness(16, 0);
			panelGrid.ColumnSpacing = 10;
			panelGrid.RowSpacing = 10;

			for (int column = 0; column < s_topItemColumns; column++)
			{
				ColumnDefinition columnDefinition = new ColumnDefinition();
				columnDefinition.Width = GridLength.Star;
				panelGrid.ColumnDefinitions.Add(columnDefinition);
			}

			int rowCount = s_topItemCount / s_topItemColumns;
			for (int row = 0; row < rowCount; row++)
			{
				RowDefinition rowDefinition = new RowDefinition();
				rowDefinition.Height = GridLength.Auto;
				panelGrid.RowDefinitions.Add(rowDefinition);
			}

			m_topTiles = new HomeTopTile[s_topItemCount];
			for (int index = 0; index < s_topItemCount; index++)
			{
				HomeTopTile tile = new HomeTopTile();
				tile.IsVisible = false;
				Grid.SetColumn(tile, index % s_topItemColumns);
				Grid.SetRow(tile, index / s_topItemColumns);
				panelGrid.Children.Add(tile);
				m_topTiles[index] = tile;
			}

			section.Children.Add(panelGrid);
			return section;
		}

		private CollectionView BuildShelf(StackLayout parent, string title)
		{
			Label header = new Label();
			header.Text = title;
			header.FontSize = 18;
			header.TextColor = ThumpColors.OnBackground;
			header.Padding = new Thickness(16, 0);
			parent.Children.Add(header);

			CollectionView collectionView = new CollectionView();
			collectionView.ItemsLayout = LinearItemsLayout.Horizontal;
			collectionView.HeightRequest = 220;
			collectionView.ItemTemplate = new DataTemplate(typeof(HomeCarouselTile));
			parent.Children.Add(collectionView);

			return collectionView;
		}

		public override void Initialize()
		{
			base.Initialize();
			MainView.Data.GetTopItems(OnTopItemsLoaded);
			MainView.Data.GetRecentlyPlayed(OnRecentlyPlayedLoaded);
			MainView.Data.GetTopPlaylists(OnYourPlaylistsLoaded);
			MainView.Data.GetPopularArtists(OnPopularArtistsLoaded);
			MainView.Data.GetRecentlyAdded(OnRecentlyAddedLoaded);
			MainView.Data.GetFavories(OnFavoritesLoaded);
		}

		private void OnTopItemsLoaded(List<PulseObject> items)
		{
			if (items == null)
			{
				return;
			}
			for (int index = 0; index < m_topTiles.Length; index++)
			{
				HomeTopTile tile = m_topTiles[index];
				if (index < items.Count)
				{
					tile.BindingContext = items[index];
					tile.IsVisible = true;
				}
				else
				{
					tile.BindingContext = null;
					tile.IsVisible = false;
				}
			}
		}

		private void OnRecentlyPlayedLoaded(List<PulseObject> items)
		{
			m_recentlyPlayed.ItemsSource = items;
		}

		private void OnYourPlaylistsLoaded(List<PulsePlaylist> items)
		{
			m_yourPlaylists.ItemsSource = items;
		}

		private void OnPopularArtistsLoaded(List<PulseArtist> items)
		{
			m_popularArtists.ItemsSource = items;
		}

		private void OnRecentlyAddedLoaded(List<PulseObject> items)
		{
			m_recentlyAdded.ItemsSource = items;
		}

		private void OnFavoritesLoaded(List<PulseObject> items)
		{
			m_favorites.ItemsSource = items;
		}
	}
}

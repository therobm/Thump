using System.Collections.Generic;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Thump.Pulse;
using Thump.Views.Tiles;

namespace Thump.Views
{
	public class HomeView : ThumpView
	{
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

			m_recentlyPlayed = BuildShelf(stack, "Recently Played");
			m_yourPlaylists = BuildShelf(stack, "Your Playlists");
			m_popularArtists = BuildShelf(stack, "Popular Artists");
			m_recentlyAdded = BuildShelf(stack, "Recently Added");
			m_favorites = BuildShelf(stack, "Favorites");

			ScrollView scroll = new ScrollView();
			scroll.Content = stack;
			Content = scroll;
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
			collectionView.HeightRequest = 180;
			collectionView.ItemTemplate = new DataTemplate(typeof(HomeCarouselTile));
			parent.Children.Add(collectionView);

			return collectionView;
		}

		public override void Initialize()
		{
			base.Initialize();
			MainView.Data.GetRecentlyPlayed(OnRecentlyPlayedLoaded);
			MainView.Data.GetTopPlaylists(OnYourPlaylistsLoaded);
			MainView.Data.GetPopularArtists(OnPopularArtistsLoaded);
			MainView.Data.GetRecentlyAdded(OnRecentlyAddedLoaded);
			MainView.Data.GetFavories(OnFavoritesLoaded);
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

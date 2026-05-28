using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Pulse;
using Thump.Views.Tiles;

namespace Thump.Views
{
	public class SearchView : ThumpView
	{
		private Entry m_searchEntry;
		private Label m_artistsHeader;
		private Label m_albumsHeader;
		private Label m_songsHeader;
		private CollectionView m_artistResults;
		private CollectionView m_albumResults;
		private CollectionView m_songResults;

		public SearchView(MainView mainView) : base(mainView)
		{
			
		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Background;

			Grid grid = new Grid();

			RowDefinition titleRow = new RowDefinition();
			titleRow.Height = GridLength.Auto;
			RowDefinition entryRow = new RowDefinition();
			entryRow.Height = GridLength.Auto;
			RowDefinition resultsRow = new RowDefinition();
			resultsRow.Height = GridLength.Star;
			grid.RowDefinitions.Add(titleRow);
			grid.RowDefinitions.Add(entryRow);
			grid.RowDefinitions.Add(resultsRow);

			grid.Children.Add(BuildTitle());
			grid.Children.Add(BuildSearchEntry());
			grid.Children.Add(BuildResults());

			Content = grid;
		}

		private View BuildTitle()
		{
			Label header = new Label();
			header.Text = "Search";
			header.FontSize = 24;
			header.TextColor = ThumpColors.OnBackground;
			header.Padding = new Thickness(16, 12);

			Grid.SetRow(header, 0);
			return header;
		}

		private View BuildSearchEntry()
		{
			m_searchEntry = new Entry();
			m_searchEntry.Placeholder = "Search artists, albums, songs";
			m_searchEntry.PlaceholderColor = ThumpColors.TextDim;
			m_searchEntry.TextColor = ThumpColors.OnBackground;
			m_searchEntry.BackgroundColor = ThumpColors.Surface;
			m_searchEntry.FontSize = 15;
			m_searchEntry.Margin = new Thickness(16, 0, 16, 12);
			m_searchEntry.Completed += OnSearchCompleted;

			Grid.SetRow(m_searchEntry, 1);
			return m_searchEntry;
		}

		private View BuildResults()
		{
			ScrollView scroll = new ScrollView();

			StackLayout stack = new StackLayout();
			stack.Spacing = 16;

			m_artistsHeader = new Label();
			m_artistsHeader.Text = "Artists";
			m_artistsHeader.FontSize = 16;
			m_artistsHeader.TextColor = ThumpColors.OnBackground;
			m_artistsHeader.Padding = new Thickness(16, 0);
			stack.Children.Add(m_artistsHeader);

			m_artistResults = new CollectionView();
			m_artistResults.ItemTemplate = new DataTemplate(typeof(ArtistRowTile));
			stack.Children.Add(m_artistResults);

			m_albumsHeader = new Label();
			m_albumsHeader.Text = "Albums";
			m_albumsHeader.FontSize = 16;
			m_albumsHeader.TextColor = ThumpColors.OnBackground;
			m_albumsHeader.Padding = new Thickness(16, 0);
			stack.Children.Add(m_albumsHeader);

			m_albumResults = new CollectionView();
			m_albumResults.ItemTemplate = new DataTemplate(typeof(AlbumRowTile));
			stack.Children.Add(m_albumResults);

			m_songsHeader = new Label();
			m_songsHeader.Text = "Songs";
			m_songsHeader.FontSize = 16;
			m_songsHeader.TextColor = ThumpColors.OnBackground;
			m_songsHeader.Padding = new Thickness(16, 0);
			stack.Children.Add(m_songsHeader);

			m_songResults = new CollectionView();
			m_songResults.ItemTemplate = new DataTemplate(typeof(TrackRowTile));
			stack.Children.Add(m_songResults);

			m_artistsHeader.IsVisible = false;
			m_artistResults.IsVisible = false;
			m_albumsHeader.IsVisible = false;
			m_albumResults.IsVisible = false;
			m_songsHeader.IsVisible = false;
			m_songResults.IsVisible = false;

			scroll.Content = stack;

			Grid.SetRow(scroll, 2);
			return scroll;
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		private void OnSearchCompleted(object sender, EventArgs e)
		{
			string query = m_searchEntry.Text;
			if (string.IsNullOrWhiteSpace(query))
			{
				return;
			}
			MainView.Data.Search(query, OnSearchResults);
		}

		private void OnSearchResults(PulseSearchData results)
		{
			if (results == null)
			{
				m_artistResults.ItemsSource = null;
				m_artistResults.IsVisible = false;
				m_artistsHeader.IsVisible = false;
				m_albumResults.ItemsSource = null;
				m_albumResults.IsVisible = false;
				m_albumsHeader.IsVisible = false;
				m_songResults.ItemsSource = null;
				m_songResults.IsVisible = false;
				m_songsHeader.IsVisible = false;
				return;
			}
			if (results.Artists != null && results.Artists.Count > 0)
			{
				m_artistResults.ItemsSource = results.Artists;
				m_artistResults.IsVisible = true;
				m_artistsHeader.IsVisible = true;
			}
			else
			{
				m_artistResults.ItemsSource = null;
				m_artistResults.IsVisible = false;
				m_artistsHeader.IsVisible = false;
			}
			if (results.Albums != null && results.Albums.Count > 0)
			{
				m_albumResults.ItemsSource = results.Albums;
				m_albumResults.IsVisible = true;
				m_albumsHeader.IsVisible = true;
			}
			else
			{
				m_albumResults.ItemsSource = null;
				m_albumResults.IsVisible = false;
				m_albumsHeader.IsVisible = false;
			}
			if (results.Songs != null && results.Songs.Count > 0)
			{
				m_songResults.ItemsSource = results.Songs;
				m_songResults.IsVisible = true;
				m_songsHeader.IsVisible = true;
			}
			else
			{
				m_songResults.ItemsSource = null;
				m_songResults.IsVisible = false;
				m_songsHeader.IsVisible = false;
			}
		}
	}
}

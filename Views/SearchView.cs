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

			Label artistsHeader = new Label();
			artistsHeader.Text = "Artists";
			artistsHeader.FontSize = 16;
			artistsHeader.TextColor = ThumpColors.OnBackground;
			artistsHeader.Padding = new Thickness(16, 0);
			stack.Children.Add(artistsHeader);

			m_artistResults = new CollectionView();
			m_artistResults.ItemTemplate = new DataTemplate(typeof(ArtistRowTile));
			stack.Children.Add(m_artistResults);

			Label albumsHeader = new Label();
			albumsHeader.Text = "Albums";
			albumsHeader.FontSize = 16;
			albumsHeader.TextColor = ThumpColors.OnBackground;
			albumsHeader.Padding = new Thickness(16, 0);
			stack.Children.Add(albumsHeader);

			m_albumResults = new CollectionView();
			m_albumResults.ItemTemplate = new DataTemplate(typeof(AlbumRowTile));
			stack.Children.Add(m_albumResults);

			Label songsHeader = new Label();
			songsHeader.Text = "Songs";
			songsHeader.FontSize = 16;
			songsHeader.TextColor = ThumpColors.OnBackground;
			songsHeader.Padding = new Thickness(16, 0);
			stack.Children.Add(songsHeader);

			m_songResults = new CollectionView();
			m_songResults.ItemTemplate = new DataTemplate(typeof(TrackRowTile));
			stack.Children.Add(m_songResults);

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
				m_albumResults.ItemsSource = null;
				m_songResults.ItemsSource = null;
				return;
			}
			m_artistResults.ItemsSource = results.Artists;
			m_albumResults.ItemsSource = results.Albums;
			m_songResults.ItemsSource = results.Songs;
		}
	}
}

using System;
using System.Collections.Generic;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Pulse;
using Thump.Views.Tiles;

namespace Thump.Views
{
	public class GenreDetailView : ThumpView
	{
		private Label m_titleLabel;
		private Label m_metaLabel;
		private CollectionView m_trackList;
		private PulseGenre m_genre;
		private List<PulseTrack> m_tracks;

		public GenreDetailView(MainView mainView, PulseGenre genre) : base(mainView)
		{
			m_genre = genre;
		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Background;

			Grid grid = new Grid();

			RowDefinition headerRow = new RowDefinition();
			headerRow.Height = GridLength.Auto;
			RowDefinition titleRow = new RowDefinition();
			titleRow.Height = GridLength.Auto;
			RowDefinition buttonRow = new RowDefinition();
			buttonRow.Height = GridLength.Auto;
			RowDefinition listRow = new RowDefinition();
			listRow.Height = GridLength.Star;
			grid.RowDefinitions.Add(headerRow);
			grid.RowDefinitions.Add(titleRow);
			grid.RowDefinitions.Add(buttonRow);
			grid.RowDefinitions.Add(listRow);

			grid.Children.Add(BuildHeader());
			grid.Children.Add(BuildTitle());
			grid.Children.Add(BuildButtons());
			grid.Children.Add(BuildTrackList());

			Content = grid;
		}

		private View BuildHeader()
		{
			HorizontalStackLayout headerStack = new HorizontalStackLayout();
			headerStack.Padding = new Thickness(8, 8, 8, 0);

			Button backButton = new Button();
			backButton.Text = "‹";
			backButton.FontSize = 22;
			backButton.TextColor = ThumpColors.OnBackground;
			backButton.BackgroundColor = Colors.Transparent;
			backButton.WidthRequest = 44;
			backButton.HeightRequest = 44;
			backButton.Clicked += OnBackClicked;
			headerStack.Children.Add(backButton);

			Grid.SetRow(headerStack, 0);
			return headerStack;
		}

		private View BuildTitle()
		{
			StackLayout titleStack = new StackLayout();
			titleStack.Spacing = 4;
			titleStack.Padding = new Thickness(16, 12, 16, 12);

			m_titleLabel = new Label();
			m_titleLabel.Text = "Genre";
			m_titleLabel.FontSize = 22;
			m_titleLabel.TextColor = ThumpColors.OnBackground;
			titleStack.Children.Add(m_titleLabel);

			m_metaLabel = new Label();
			m_metaLabel.Text = "";
			m_metaLabel.FontSize = 12;
			m_metaLabel.TextColor = ThumpColors.TextDim;
			titleStack.Children.Add(m_metaLabel);

			Grid.SetRow(titleStack, 1);
			return titleStack;
		}

		private View BuildButtons()
		{
			HorizontalStackLayout buttonStack = new HorizontalStackLayout();
			buttonStack.Spacing = 12;
			buttonStack.Padding = new Thickness(16, 0, 16, 12);

			Button playButton = new Button();
			playButton.Text = "▶  Play";
			playButton.TextColor = ThumpColors.Background;
			playButton.BackgroundColor = ThumpColors.Accent;
			playButton.CornerRadius = 8;
			playButton.FontSize = 14;
			playButton.Padding = new Thickness(20, 8);
			playButton.Clicked += OnPlayClicked;
			buttonStack.Children.Add(playButton);

			Button shuffleButton = new Button();
			shuffleButton.Text = "⇋  Shuffle";
			shuffleButton.TextColor = ThumpColors.OnBackground;
			shuffleButton.BackgroundColor = ThumpColors.Surface;
			shuffleButton.CornerRadius = 8;
			shuffleButton.FontSize = 14;
			shuffleButton.Padding = new Thickness(20, 8);
			shuffleButton.Clicked += OnShuffleClicked;
			buttonStack.Children.Add(shuffleButton);

			Button queueButton = new Button();
			queueButton.Text = "＋  Queue";
			queueButton.TextColor = ThumpColors.OnBackground;
			queueButton.BackgroundColor = ThumpColors.Surface;
			queueButton.CornerRadius = 8;
			queueButton.FontSize = 14;
			queueButton.Padding = new Thickness(20, 8);
			queueButton.Clicked += OnAddToQueueClicked;
			buttonStack.Children.Add(queueButton);

			Button nextButton = new Button();
			nextButton.Text = "⤓  Play Next";
			nextButton.TextColor = ThumpColors.OnBackground;
			nextButton.BackgroundColor = ThumpColors.Surface;
			nextButton.CornerRadius = 8;
			nextButton.FontSize = 14;
			nextButton.Padding = new Thickness(20, 8);
			nextButton.Clicked += OnPlayNextClicked;
			buttonStack.Children.Add(nextButton);

			ScrollView scroller = new ScrollView();
			scroller.Orientation = ScrollOrientation.Horizontal;
			scroller.HorizontalScrollBarVisibility = ScrollBarVisibility.Never;
			scroller.Content = buttonStack;

			Grid.SetRow(scroller, 2);
			return scroller;
		}

		private View BuildTrackList()
		{
			m_trackList = new CollectionView();
			m_trackList.ItemTemplate = new DataTemplate(typeof(TrackRowTile));

			Grid.SetRow(m_trackList, 3);
			return m_trackList;
		}

		public override void Initialize()
		{
			base.Initialize();
			m_titleLabel.Text = m_genre.Name;
			m_metaLabel.Text = m_genre.SongCount + " songs  ·  " + m_genre.AlbumCount + " albums";
			MainView.Data.GetTracksForGenre(m_genre, OnTracksLoaded);
		}

		private void OnTracksLoaded(List<PulseTrack> tracks)
		{
			m_tracks = tracks;
			m_trackList.ItemsSource = tracks;
		}

		private void OnBackClicked(object sender, EventArgs e)
		{
			m_mainView.OnBackPressed();
		}

		private void OnPlayClicked(object sender, EventArgs e)
		{
			m_mainView.OnPlayTracks(m_tracks, 0);
		}

		private void OnShuffleClicked(object sender, EventArgs e)
		{
			m_mainView.OnPlayTracksShuffled(m_tracks);
		}

		private void OnAddToQueueClicked(object sender, EventArgs e)
		{
			m_mainView.OnAddToQueue(m_tracks);
		}

		private void OnPlayNextClicked(object sender, EventArgs e)
		{
			m_mainView.OnPlayNext(m_tracks);
		}
	}
}

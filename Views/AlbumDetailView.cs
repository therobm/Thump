using System;
using System.Collections.Generic;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Pulse;
using Thump.Views.Tiles;

namespace Thump.Views
{
	public class AlbumDetailView : ThumpView
	{
		private ArtImage m_art;
		private Label m_titleLabel;
		private Label m_artistLabel;
		private Label m_metaLabel;
		private CollectionView m_trackList;
		private PulseAlbum m_album;
		private List<PulseTrack> m_tracks;

		public AlbumDetailView(MainView mainView, PulseAlbum album) : base(mainView)
		{
			m_album = album;
			
		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Background;

			Grid grid = new Grid();

			RowDefinition headerRow = new RowDefinition();
			headerRow.Height = GridLength.Auto;
			RowDefinition artRow = new RowDefinition();
			artRow.Height = GridLength.Auto;
			RowDefinition titleRow = new RowDefinition();
			titleRow.Height = GridLength.Auto;
			RowDefinition buttonRow = new RowDefinition();
			buttonRow.Height = GridLength.Auto;
			RowDefinition listRow = new RowDefinition();
			listRow.Height = GridLength.Star;
			grid.RowDefinitions.Add(headerRow);
			grid.RowDefinitions.Add(artRow);
			grid.RowDefinitions.Add(titleRow);
			grid.RowDefinitions.Add(buttonRow);
			grid.RowDefinitions.Add(listRow);

			grid.Children.Add(BuildHeader());
			grid.Children.Add(BuildArt());
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

		private View BuildArt()
		{
			m_art = new ArtImage();
			m_art.HeightRequest = 220;
			m_art.WidthRequest = 220;
			m_art.HorizontalOptions = LayoutOptions.Center;
			m_art.Margin = new Thickness(0, 12, 0, 16);

			Grid.SetRow(m_art, 1);
			return m_art;
		}

		private View BuildTitle()
		{
			StackLayout titleStack = new StackLayout();
			titleStack.Spacing = 4;
			titleStack.Padding = new Thickness(16, 0, 16, 12);

			m_titleLabel = new Label();
			m_titleLabel.Text = "Album name";
			m_titleLabel.FontSize = 22;
			m_titleLabel.TextColor = ThumpColors.OnBackground;
			m_titleLabel.HorizontalOptions = LayoutOptions.Center;
			titleStack.Children.Add(m_titleLabel);

			m_artistLabel = new Label();
			m_artistLabel.Text = "Artist";
			m_artistLabel.FontSize = 14;
			m_artistLabel.TextColor = ThumpColors.TextSecondary;
			m_artistLabel.HorizontalOptions = LayoutOptions.Center;
			titleStack.Children.Add(m_artistLabel);

			m_metaLabel = new Label();
			m_metaLabel.Text = "";
			m_metaLabel.FontSize = 12;
			m_metaLabel.TextColor = ThumpColors.TextDim;
			m_metaLabel.HorizontalOptions = LayoutOptions.Center;
			titleStack.Children.Add(m_metaLabel);

			Grid.SetRow(titleStack, 2);
			return titleStack;
		}

		private View BuildButtons()
		{
			HorizontalStackLayout buttonStack = new HorizontalStackLayout();
			buttonStack.Spacing = 12;
			buttonStack.Padding = new Thickness(16, 0, 16, 12);
			buttonStack.HorizontalOptions = LayoutOptions.Center;

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

			Grid.SetRow(buttonStack, 3);
			return buttonStack;
		}

		private View BuildTrackList()
		{
			m_trackList = new CollectionView();
			m_trackList.ItemTemplate = new DataTemplate(typeof(TrackRowTile));

			Grid.SetRow(m_trackList, 4);
			return m_trackList;
		}

		public override void Initialize()
		{
			base.Initialize();
			m_titleLabel.Text = m_album.Name;
			m_artistLabel.Text = m_album.Artist;
			m_metaLabel.Text = m_album.Year + "  ·  " + m_album.SongCount + " tracks";
			m_art.SetCoverArt(m_album.CoverArt);
			MainView.Data.GetTracksForAlbum(m_album, OnTracksLoaded);
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
			m_mainView.OnPlayTracks(m_tracks, 0);
		}
	}
}

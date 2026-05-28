using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Pulse;

namespace Thump.Views
{
	public class NowPlayingView : ThumpView
	{
		private ArtImage m_art;
		private Label m_titleLabel;
		private Label m_artistLabel;
		private Label m_currentTimeLabel;
		private Slider m_seekSlider;
		private Label m_totalTimeLabel;
		private Button m_playPauseButton;

		public NowPlayingView(MainView mainView) : base(mainView)
		{
			
		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Background;

			Grid grid = new Grid();

			RowDefinition headerRow = new RowDefinition();
			headerRow.Height = GridLength.Auto;
			RowDefinition artRow = new RowDefinition();
			artRow.Height = GridLength.Star;
			RowDefinition titleRow = new RowDefinition();
			titleRow.Height = GridLength.Auto;
			RowDefinition seekRow = new RowDefinition();
			seekRow.Height = GridLength.Auto;
			RowDefinition controlsRow = new RowDefinition();
			controlsRow.Height = GridLength.Auto;
			RowDefinition actionsRow = new RowDefinition();
			actionsRow.Height = GridLength.Auto;
			grid.RowDefinitions.Add(headerRow);
			grid.RowDefinitions.Add(artRow);
			grid.RowDefinitions.Add(titleRow);
			grid.RowDefinitions.Add(seekRow);
			grid.RowDefinitions.Add(controlsRow);
			grid.RowDefinitions.Add(actionsRow);

			grid.Children.Add(BuildHeader());
			grid.Children.Add(BuildArt());
			grid.Children.Add(BuildTitle());
			grid.Children.Add(BuildSeekBar());
			grid.Children.Add(BuildTransport());
			grid.Children.Add(BuildActions());

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
			m_art.WidthRequest = 280;
			m_art.HeightRequest = 280;
			m_art.HorizontalOptions = LayoutOptions.Center;
			m_art.VerticalOptions = LayoutOptions.Center;

			Grid.SetRow(m_art, 1);
			return m_art;
		}

		private View BuildTitle()
		{
			StackLayout titleStack = new StackLayout();
			titleStack.Spacing = 4;
			titleStack.Padding = new Thickness(24, 16, 24, 8);

			m_titleLabel = new Label();
			m_titleLabel.Text = "Track title";
			m_titleLabel.FontSize = 20;
			m_titleLabel.TextColor = ThumpColors.OnBackground;
			m_titleLabel.HorizontalOptions = LayoutOptions.Center;
			m_titleLabel.LineBreakMode = LineBreakMode.TailTruncation;
			titleStack.Children.Add(m_titleLabel);

			m_artistLabel = new Label();
			m_artistLabel.Text = "Artist";
			m_artistLabel.FontSize = 14;
			m_artistLabel.TextColor = ThumpColors.TextSecondary;
			m_artistLabel.HorizontalOptions = LayoutOptions.Center;
			m_artistLabel.LineBreakMode = LineBreakMode.TailTruncation;
			titleStack.Children.Add(m_artistLabel);

			Grid.SetRow(titleStack, 2);
			return titleStack;
		}

		private View BuildSeekBar()
		{
			Grid seekGrid = new Grid();
			seekGrid.Padding = new Thickness(24, 8, 24, 4);

			ColumnDefinition currentTimeColumn = new ColumnDefinition();
			currentTimeColumn.Width = GridLength.Auto;
			ColumnDefinition sliderColumn = new ColumnDefinition();
			sliderColumn.Width = GridLength.Star;
			ColumnDefinition totalTimeColumn = new ColumnDefinition();
			totalTimeColumn.Width = GridLength.Auto;
			seekGrid.ColumnDefinitions.Add(currentTimeColumn);
			seekGrid.ColumnDefinitions.Add(sliderColumn);
			seekGrid.ColumnDefinitions.Add(totalTimeColumn);

			m_currentTimeLabel = new Label();
			m_currentTimeLabel.Text = "0:00";
			m_currentTimeLabel.FontSize = 11;
			m_currentTimeLabel.TextColor = ThumpColors.TextDim;
			m_currentTimeLabel.VerticalOptions = LayoutOptions.Center;
			Grid.SetColumn(m_currentTimeLabel, 0);
			seekGrid.Children.Add(m_currentTimeLabel);

			m_seekSlider = new Slider();
			m_seekSlider.Minimum = 0;
			m_seekSlider.Maximum = 100;
			m_seekSlider.Value = 0;
			m_seekSlider.MinimumTrackColor = ThumpColors.Accent;
			m_seekSlider.MaximumTrackColor = ThumpColors.Divider;
			m_seekSlider.ThumbColor = ThumpColors.Accent;
			Grid.SetColumn(m_seekSlider, 1);
			seekGrid.Children.Add(m_seekSlider);

			m_totalTimeLabel = new Label();
			m_totalTimeLabel.Text = "0:00";
			m_totalTimeLabel.FontSize = 11;
			m_totalTimeLabel.TextColor = ThumpColors.TextDim;
			m_totalTimeLabel.VerticalOptions = LayoutOptions.Center;
			Grid.SetColumn(m_totalTimeLabel, 2);
			seekGrid.Children.Add(m_totalTimeLabel);

			Grid.SetRow(seekGrid, 3);
			return seekGrid;
		}

		private View BuildTransport()
		{
			HorizontalStackLayout controlsStack = new HorizontalStackLayout();
			controlsStack.Spacing = 16;
			controlsStack.HorizontalOptions = LayoutOptions.Center;
			controlsStack.Padding = new Thickness(0, 8, 0, 8);

			Button shuffleButton = new Button();
			shuffleButton.Text = "⇋";
			shuffleButton.FontSize = 20;
			shuffleButton.TextColor = ThumpColors.TextSecondary;
			shuffleButton.BackgroundColor = Colors.Transparent;
			shuffleButton.WidthRequest = 48;
			shuffleButton.HeightRequest = 48;
			shuffleButton.Clicked += OnShuffleClicked;
			controlsStack.Children.Add(shuffleButton);

			Button prevButton = new Button();
			prevButton.Text = "⏮";
			prevButton.FontSize = 22;
			prevButton.TextColor = ThumpColors.OnBackground;
			prevButton.BackgroundColor = Colors.Transparent;
			prevButton.WidthRequest = 56;
			prevButton.HeightRequest = 56;
			prevButton.Clicked += OnPrevClicked;
			controlsStack.Children.Add(prevButton);

			m_playPauseButton = new Button();
			m_playPauseButton.Text = "▶";
			m_playPauseButton.FontSize = 26;
			m_playPauseButton.TextColor = ThumpColors.Background;
			m_playPauseButton.BackgroundColor = ThumpColors.Accent;
			m_playPauseButton.CornerRadius = 32;
			m_playPauseButton.WidthRequest = 64;
			m_playPauseButton.HeightRequest = 64;
			m_playPauseButton.Clicked += OnPlayPauseClicked;
			controlsStack.Children.Add(m_playPauseButton);

			Button nextButton = new Button();
			nextButton.Text = "⏭";
			nextButton.FontSize = 22;
			nextButton.TextColor = ThumpColors.OnBackground;
			nextButton.BackgroundColor = Colors.Transparent;
			nextButton.WidthRequest = 56;
			nextButton.HeightRequest = 56;
			nextButton.Clicked += OnNextClicked;
			controlsStack.Children.Add(nextButton);

			Button repeatButton = new Button();
			repeatButton.Text = "↻";
			repeatButton.FontSize = 20;
			repeatButton.TextColor = ThumpColors.TextSecondary;
			repeatButton.BackgroundColor = Colors.Transparent;
			repeatButton.WidthRequest = 48;
			repeatButton.HeightRequest = 48;
			repeatButton.Clicked += OnRepeatClicked;
			controlsStack.Children.Add(repeatButton);

			Grid.SetRow(controlsStack, 4);
			return controlsStack;
		}

		private View BuildActions()
		{
			HorizontalStackLayout actionsStack = new HorizontalStackLayout();
			actionsStack.Spacing = 16;
			actionsStack.HorizontalOptions = LayoutOptions.Center;
			actionsStack.Padding = new Thickness(0, 0, 0, 16);

			Button favoriteButton = new Button();
			favoriteButton.Text = "♡  Favorite";
			favoriteButton.TextColor = ThumpColors.TextSecondary;
			favoriteButton.BackgroundColor = Colors.Transparent;
			favoriteButton.FontSize = 13;
			favoriteButton.Clicked += OnFavoriteClicked;
			actionsStack.Children.Add(favoriteButton);

			Button queueButton = new Button();
			queueButton.Text = "≣  Queue";
			queueButton.TextColor = ThumpColors.TextSecondary;
			queueButton.BackgroundColor = Colors.Transparent;
			queueButton.FontSize = 13;
			queueButton.Clicked += OnQueueClicked;
			actionsStack.Children.Add(queueButton);

			Grid.SetRow(actionsStack, 5);
			return actionsStack;
		}

		public override void Initialize()
		{
			base.Initialize();
			PulseTrack song = MainView.Self.GetCurrentTrack();
			SetTrack(song);
		}

		public void SetTrack(PulseTrack song)
		{
			if (song == null)
			{
				m_titleLabel.Text = "Nothing playing";
				m_artistLabel.Text = "";
				m_currentTimeLabel.Text = "0:00";
				m_totalTimeLabel.Text = "0:00";
				m_seekSlider.Value = 0;
				return;
			}
			m_titleLabel.Text = song.Title;
			m_artistLabel.Text = song.Artist;
			m_currentTimeLabel.Text = "0:00";
			m_totalTimeLabel.Text = FormatDuration(song.Duration);
			m_seekSlider.Value = 0;
			m_art.SetCoverArt(song.ImageID);
		}

		private static string FormatDuration(int totalSeconds)
		{
			int minutes = totalSeconds / 60;
			int seconds = totalSeconds % 60;
			string secondsText;
			if (seconds < 10)
			{
				secondsText = "0" + seconds;
			}
			else
			{
				secondsText = seconds.ToString();
			}
			return minutes + ":" + secondsText;
		}

		private void OnBackClicked(object sender, EventArgs e)
		{
			m_mainView.OnBackPressed();
		}

		private void OnPlayPauseClicked(object sender, EventArgs e)
		{
		}

		private void OnPrevClicked(object sender, EventArgs e)
		{
		}

		private void OnNextClicked(object sender, EventArgs e)
		{
		}

		private void OnShuffleClicked(object sender, EventArgs e)
		{
		}

		private void OnRepeatClicked(object sender, EventArgs e)
		{
		}

		private void OnFavoriteClicked(object sender, EventArgs e)
		{
		}

		private void OnQueueClicked(object sender, EventArgs e)
		{
		}
	}
}

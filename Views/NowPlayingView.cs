using System;
using System.Collections.Generic;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Playback;
using Thump.Pulse;
using Thump.Views.Tiles;

namespace Thump.Views
{
	public class NowPlayingView : ThumpView
	{
		private ArtImage m_art;
		private Label m_titleLabel;
		private Label m_albumLabel;
		private Label m_artistLabel;
		private Label m_currentTimeLabel;
		private Slider m_seekSlider;
		private Label m_totalTimeLabel;
		private Button m_playPauseButton;
		private Button m_shuffleButton;
		private Button m_repeatButton;
		private CollectionView m_queueList;
		private bool m_showingQueue;
		private bool m_userSeeking;

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
			RowDefinition bottomRow = new RowDefinition();
			bottomRow.Height = GridLength.Auto;
			grid.RowDefinitions.Add(headerRow);
			grid.RowDefinitions.Add(artRow);
			grid.RowDefinitions.Add(bottomRow);

			grid.Children.Add(BuildHeader());
			grid.Children.Add(BuildArt());
			grid.Children.Add(BuildQueuePanel());
			grid.Children.Add(BuildBottomBlock());

			Content = grid;
		}

		private View BuildHeader()
		{
			HorizontalStackLayout headerStack = new HorizontalStackLayout();
			headerStack.Padding = new Thickness(8, 8, 8, 0);

			Button backButton = new Button();
			backButton.Text = "‹";
			backButton.FontSize = 24;
			backButton.TextColor = ThumpColors.OnBackground;
			backButton.BackgroundColor = Colors.Transparent;
			backButton.WidthRequest = 48;
			backButton.HeightRequest = 48;
			backButton.Clicked += OnBackClicked;
			headerStack.Children.Add(backButton);

			Grid.SetRow(headerStack, 0);
			return headerStack;
		}

		private View BuildArt()
		{
			m_art = new ArtImage();
			m_art.SetAspect(Aspect.AspectFit);
			m_art.Margin = new Thickness(24, 16, 24, 8);
			m_art.HorizontalOptions = LayoutOptions.Fill;
			m_art.VerticalOptions = LayoutOptions.Fill;

			Grid.SetRow(m_art, 1);
			return m_art;
		}

		private View BuildQueuePanel()
		{
			m_queueList = new CollectionView();
			m_queueList.ItemTemplate = new DataTemplate(typeof(QueueRowTile));
			m_queueList.Margin = new Thickness(0, 8, 0, 8);
			m_queueList.IsVisible = false;

			Grid.SetRow(m_queueList, 1);
			return m_queueList;
		}

		private View BuildBottomBlock()
		{
			VerticalStackLayout bottomStack = new VerticalStackLayout();
			bottomStack.Spacing = 4;

			bottomStack.Children.Add(BuildTitle());
			bottomStack.Children.Add(BuildSeekBar());
			bottomStack.Children.Add(BuildTransport());
			bottomStack.Children.Add(BuildActions());

			Grid.SetRow(bottomStack, 2);
			return bottomStack;
		}

		private View BuildTitle()
		{
			VerticalStackLayout titleStack = new VerticalStackLayout();
			titleStack.Spacing = 2;
			titleStack.Padding = new Thickness(24, 8, 24, 4);
			titleStack.HorizontalOptions = LayoutOptions.Start;

			m_titleLabel = new Label();
			m_titleLabel.Text = "Track title";
			m_titleLabel.FontSize = 24;
			m_titleLabel.FontAttributes = FontAttributes.Bold;
			m_titleLabel.TextColor = ThumpColors.OnBackground;
			m_titleLabel.HorizontalOptions = LayoutOptions.Start;
			m_titleLabel.HorizontalTextAlignment = TextAlignment.Start;
			m_titleLabel.LineBreakMode = LineBreakMode.TailTruncation;
			titleStack.Children.Add(m_titleLabel);

			m_albumLabel = new Label();
			m_albumLabel.Text = "Album";
			m_albumLabel.FontSize = 15;
			m_albumLabel.TextColor = ThumpColors.TextSecondary;
			m_albumLabel.HorizontalOptions = LayoutOptions.Start;
			m_albumLabel.HorizontalTextAlignment = TextAlignment.Start;
			m_albumLabel.LineBreakMode = LineBreakMode.TailTruncation;
			titleStack.Children.Add(m_albumLabel);

			m_artistLabel = new Label();
			m_artistLabel.Text = "Artist";
			m_artistLabel.FontSize = 14;
			m_artistLabel.TextColor = ThumpColors.TextDim;
			m_artistLabel.HorizontalOptions = LayoutOptions.Start;
			m_artistLabel.HorizontalTextAlignment = TextAlignment.Start;
			m_artistLabel.LineBreakMode = LineBreakMode.TailTruncation;
			titleStack.Children.Add(m_artistLabel);

			return titleStack;
		}

		private View BuildSeekBar()
		{
			Grid seekGrid = new Grid();
			seekGrid.Padding = new Thickness(24, 4, 24, 4);

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
			m_seekSlider.DragStarted += OnSeekDragStarted;
			m_seekSlider.DragCompleted += OnSeekDragCompleted;
			Grid.SetColumn(m_seekSlider, 1);
			seekGrid.Children.Add(m_seekSlider);

			m_totalTimeLabel = new Label();
			m_totalTimeLabel.Text = "0:00";
			m_totalTimeLabel.FontSize = 11;
			m_totalTimeLabel.TextColor = ThumpColors.TextDim;
			m_totalTimeLabel.VerticalOptions = LayoutOptions.Center;
			Grid.SetColumn(m_totalTimeLabel, 2);
			seekGrid.Children.Add(m_totalTimeLabel);

			return seekGrid;
		}

		private View BuildTransport()
		{
			HorizontalStackLayout controlsStack = new HorizontalStackLayout();
			controlsStack.Spacing = 18;
			controlsStack.HorizontalOptions = LayoutOptions.Center;
			controlsStack.Padding = new Thickness(0, 8, 0, 8);

			m_shuffleButton = new Button();
			m_shuffleButton.Text = "⇋";
			m_shuffleButton.FontSize = 26;
			m_shuffleButton.TextColor = ThumpColors.TextSecondary;
			m_shuffleButton.BackgroundColor = Colors.Transparent;
			m_shuffleButton.WidthRequest = 56;
			m_shuffleButton.HeightRequest = 56;
			m_shuffleButton.VerticalOptions = LayoutOptions.Center;
			m_shuffleButton.Clicked += OnShuffleClicked;
			controlsStack.Children.Add(m_shuffleButton);

			Button prevButton = new Button();
			prevButton.Text = "⏮";
			prevButton.FontSize = 30;
			prevButton.TextColor = ThumpColors.OnBackground;
			prevButton.BackgroundColor = Colors.Transparent;
			prevButton.WidthRequest = 60;
			prevButton.HeightRequest = 60;
			prevButton.VerticalOptions = LayoutOptions.Center;
			prevButton.Clicked += OnPrevClicked;
			controlsStack.Children.Add(prevButton);

			m_playPauseButton = new Button();
			m_playPauseButton.Text = "▶";
			m_playPauseButton.FontSize = 32;
			m_playPauseButton.TextColor = ThumpColors.Background;
			m_playPauseButton.BackgroundColor = ThumpColors.Accent;
			m_playPauseButton.CornerRadius = 38;
			m_playPauseButton.WidthRequest = 76;
			m_playPauseButton.HeightRequest = 76;
			m_playPauseButton.VerticalOptions = LayoutOptions.Center;
			m_playPauseButton.Clicked += OnPlayPauseClicked;
			controlsStack.Children.Add(m_playPauseButton);

			Button nextButton = new Button();
			nextButton.Text = "⏭";
			nextButton.FontSize = 30;
			nextButton.TextColor = ThumpColors.OnBackground;
			nextButton.BackgroundColor = Colors.Transparent;
			nextButton.WidthRequest = 60;
			nextButton.HeightRequest = 60;
			nextButton.VerticalOptions = LayoutOptions.Center;
			nextButton.Clicked += OnNextClicked;
			controlsStack.Children.Add(nextButton);

			m_repeatButton = new Button();
			m_repeatButton.Text = "↻";
			m_repeatButton.FontSize = 26;
			m_repeatButton.TextColor = ThumpColors.TextSecondary;
			m_repeatButton.BackgroundColor = Colors.Transparent;
			m_repeatButton.WidthRequest = 56;
			m_repeatButton.HeightRequest = 56;
			m_repeatButton.VerticalOptions = LayoutOptions.Center;
			m_repeatButton.Clicked += OnRepeatClicked;
			controlsStack.Children.Add(m_repeatButton);

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
			favoriteButton.FontSize = 16;
			favoriteButton.HeightRequest = 48;
			favoriteButton.Padding = new Thickness(16, 8);
			favoriteButton.Clicked += OnFavoriteClicked;
			actionsStack.Children.Add(favoriteButton);

			Button queueButton = new Button();
			queueButton.Text = "≣  Queue";
			queueButton.TextColor = ThumpColors.TextSecondary;
			queueButton.BackgroundColor = Colors.Transparent;
			queueButton.FontSize = 16;
			queueButton.HeightRequest = 48;
			queueButton.Padding = new Thickness(16, 8);
			queueButton.Clicked += OnQueueClicked;
			actionsStack.Children.Add(queueButton);

			return actionsStack;
		}

		public override void Initialize()
		{
			base.Initialize();
			PulseTrack song = m_mainView.GetCurrentTrack();
			SetTrack(song);
			SetShuffleState(m_mainView.GetShuffleEnabled());
			SetRepeatState(m_mainView.GetRepeatMode());
		}

		public void SetTrack(PulseTrack song)
		{
			if (song == null)
			{
				m_titleLabel.Text = "Nothing playing";
				m_albumLabel.Text = "";
				m_artistLabel.Text = "";
				m_currentTimeLabel.Text = "0:00";
				m_totalTimeLabel.Text = "0:00";
				m_seekSlider.Value = 0;
				return;
			}
			m_titleLabel.Text = song.Title;
			m_albumLabel.Text = song.Album;
			m_artistLabel.Text = song.Artist;
			m_currentTimeLabel.Text = "0:00";
			m_totalTimeLabel.Text = FormatDuration(song.Duration);
			m_seekSlider.Value = 0;
			m_art.SetCoverArt(song.ImageID);
			RefreshQueue();
		}

		public void SetPlaying(bool playing)
		{
			if (playing)
			{
				m_playPauseButton.Text = "⏸";
			}
			else
			{
				m_playPauseButton.Text = "▶";
			}
		}

		public void UpdatePosition(long positionMilliseconds, long durationMilliseconds)
		{
			if (m_userSeeking)
			{
				return;
			}
			int positionSeconds = (int)(positionMilliseconds / 1000);
			int durationSeconds = (int)(durationMilliseconds / 1000);
			m_currentTimeLabel.Text = FormatDuration(positionSeconds);
			m_totalTimeLabel.Text = FormatDuration(durationSeconds);
			if (durationMilliseconds > 0)
			{
				m_seekSlider.Value = (double)positionMilliseconds / (double)durationMilliseconds * m_seekSlider.Maximum;
			}
			else
			{
				m_seekSlider.Value = 0;
			}
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
			m_mainView.OnTogglePlayPause();
		}

		private void OnPrevClicked(object sender, EventArgs e)
		{
			m_mainView.OnPrevious();
		}

		private void OnNextClicked(object sender, EventArgs e)
		{
			m_mainView.OnNext();
		}

		private void OnSeekDragStarted(object sender, EventArgs e)
		{
			m_userSeeking = true;
		}

		private void OnSeekDragCompleted(object sender, EventArgs e)
		{
			m_userSeeking = false;
			double fraction = 0;
			if (m_seekSlider.Maximum > 0)
			{
				fraction = m_seekSlider.Value / m_seekSlider.Maximum;
			}
			m_mainView.OnSeekToFraction(fraction);
		}

		private void OnShuffleClicked(object sender, EventArgs e)
		{
			m_mainView.OnToggleShuffle();
		}

		private void OnRepeatClicked(object sender, EventArgs e)
		{
			m_mainView.OnCycleRepeat();
		}

		private void OnFavoriteClicked(object sender, EventArgs e)
		{
		}

		private void OnQueueClicked(object sender, EventArgs e)
		{
			m_showingQueue = !m_showingQueue;
			m_art.IsVisible = !m_showingQueue;
			m_queueList.IsVisible = m_showingQueue;
			if (m_showingQueue)
			{
				RefreshQueue();
			}
		}

		public void SetShuffleState(bool enabled)
		{
			if (m_shuffleButton == null)
			{
				return;
			}
			if (enabled)
			{
				m_shuffleButton.TextColor = ThumpColors.Accent;
			}
			else
			{
				m_shuffleButton.TextColor = ThumpColors.TextSecondary;
			}
		}

		public void SetRepeatState(eRepeatMode mode)
		{
			if (m_repeatButton == null)
			{
				return;
			}
			if (mode == eRepeatMode.One)
			{
				m_repeatButton.Text = "↻¹";
				m_repeatButton.TextColor = ThumpColors.Accent;
			}
			else if (mode == eRepeatMode.All)
			{
				m_repeatButton.Text = "↻";
				m_repeatButton.TextColor = ThumpColors.Accent;
			}
			else
			{
				m_repeatButton.Text = "↻";
				m_repeatButton.TextColor = ThumpColors.TextSecondary;
			}
		}

		public void RefreshQueue()
		{
			if (m_queueList == null || !m_showingQueue)
			{
				return;
			}
			List<PulseTrack> queue = m_mainView.GetQueue();
			m_queueList.ItemsSource = null;
			m_queueList.ItemsSource = queue;
		}
	}
}

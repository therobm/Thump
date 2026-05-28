using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Pulse;

namespace Thump.Views
{
	public class MiniPlayer : ThumpView
	{
		private ArtImage m_art;
		private Label m_titleLabel;
		private Label m_artistLabel;
		private Button m_playPauseButton;
		private Button m_prevButton;
		private Button m_nextButton;
		private ProgressBar m_progress;

		public MiniPlayer(MainView mainView) : base(mainView)
		{

		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Surface;
			HeightRequest = 64;

			Grid grid = new Grid();
			grid.Padding = new Thickness(8, 0);

			ColumnDefinition artColumn = new ColumnDefinition();
			artColumn.Width = new GridLength(64);
			ColumnDefinition textColumn = new ColumnDefinition();
			textColumn.Width = GridLength.Star;
			ColumnDefinition buttonColumn = new ColumnDefinition();
			buttonColumn.Width = GridLength.Auto;
			grid.ColumnDefinitions.Add(artColumn);
			grid.ColumnDefinitions.Add(textColumn);
			grid.ColumnDefinitions.Add(buttonColumn);

			grid.Children.Add(BuildArt());
			grid.Children.Add(BuildTrackInfo());
			grid.Children.Add(BuildPlayButton());

			TapGestureRecognizer tap = new TapGestureRecognizer();
			tap.Tapped += OnExpandTapped;
			grid.GestureRecognizers.Add(tap);

			Grid outer = new Grid();
			RowDefinition progressRow = new RowDefinition();
			progressRow.Height = GridLength.Auto;
			RowDefinition contentRow = new RowDefinition();
			contentRow.Height = GridLength.Star;
			outer.RowDefinitions.Add(progressRow);
			outer.RowDefinitions.Add(contentRow);

			m_progress = new ProgressBar();
			m_progress.Progress = 0;
			m_progress.HeightRequest = 2;
			m_progress.ProgressColor = ThumpColors.Accent;
			m_progress.BackgroundColor = ThumpColors.Divider;
			Grid.SetRow(m_progress, 0);
			outer.Children.Add(m_progress);

			Grid.SetRow(grid, 1);
			outer.Children.Add(grid);

			Content = outer;
		}

		private View BuildArt()
		{
			m_art = new ArtImage();
			m_art.WidthRequest = 48;
			m_art.HeightRequest = 48;
			m_art.VerticalOptions = LayoutOptions.Center;

			Grid.SetColumn(m_art, 0);
			return m_art;
		}

		private View BuildTrackInfo()
		{
			StackLayout textStack = new StackLayout();
			textStack.VerticalOptions = LayoutOptions.Center;
			textStack.Spacing = 2;

			m_titleLabel = new Label();
			m_titleLabel.Text = "Nothing playing";
			m_titleLabel.TextColor = ThumpColors.OnBackground;
			m_titleLabel.FontSize = 14;
			textStack.Children.Add(m_titleLabel);

			m_artistLabel = new Label();
			m_artistLabel.Text = "";
			m_artistLabel.TextColor = ThumpColors.TextSecondary;
			m_artistLabel.FontSize = 12;
			textStack.Children.Add(m_artistLabel);

			Grid.SetColumn(textStack, 1);
			return textStack;
		}

		private View BuildPlayButton()
		{
			HorizontalStackLayout controlsStack = new HorizontalStackLayout();
			controlsStack.Spacing = 0;
			controlsStack.VerticalOptions = LayoutOptions.Center;

			m_prevButton = new Button();
			m_prevButton.Text = "⏮";
			m_prevButton.TextColor = ThumpColors.OnBackground;
			m_prevButton.BackgroundColor = Colors.Transparent;
			m_prevButton.FontSize = 18;
			m_prevButton.WidthRequest = 44;
			m_prevButton.HeightRequest = 48;
			m_prevButton.Clicked += OnPrevClicked;
			controlsStack.Children.Add(m_prevButton);

			m_playPauseButton = new Button();
			m_playPauseButton.Text = "▶";
			m_playPauseButton.TextColor = ThumpColors.OnBackground;
			m_playPauseButton.BackgroundColor = Colors.Transparent;
			m_playPauseButton.FontSize = 20;
			m_playPauseButton.WidthRequest = 48;
			m_playPauseButton.HeightRequest = 48;
			m_playPauseButton.Clicked += OnPlayPauseClicked;
			controlsStack.Children.Add(m_playPauseButton);

			m_nextButton = new Button();
			m_nextButton.Text = "⏭";
			m_nextButton.TextColor = ThumpColors.OnBackground;
			m_nextButton.BackgroundColor = Colors.Transparent;
			m_nextButton.FontSize = 18;
			m_nextButton.WidthRequest = 44;
			m_nextButton.HeightRequest = 48;
			m_nextButton.Clicked += OnNextClicked;
			controlsStack.Children.Add(m_nextButton);

			Grid.SetColumn(controlsStack, 2);
			return controlsStack;
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		public void SetTrack(PulseTrack song)
		{
			if (song == null)
			{
				m_titleLabel.Text = "Nothing playing";
				m_artistLabel.Text = "";
				UpdateSkipButtons();
				return;
			}
			m_titleLabel.Text = song.Title;
			m_artistLabel.Text = song.Artist;
			m_art.SetCoverArt(song.ImageID);
			UpdateSkipButtons();
		}

		public void RefreshSkipButtons()
		{
			UpdateSkipButtons();
		}

		private void UpdateSkipButtons()
		{
			if (m_prevButton == null || m_nextButton == null)
			{
				return;
			}
			int index = m_mainView.GetQueueIndex();
			int count = m_mainView.GetQueue().Count;
			bool canPrevious = index > 0;
			bool canNext = index < count - 1;
			SetSkipButtonState(m_prevButton, canPrevious);
			SetSkipButtonState(m_nextButton, canNext);
		}

		private void SetSkipButtonState(Button button, bool enabled)
		{
			button.IsEnabled = enabled;
			if (enabled)
			{
				button.TextColor = ThumpColors.OnBackground;
			}
			else
			{
				button.TextColor = ThumpColors.TextDim;
			}
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

		public void SetProgress(double fraction)
		{
			if (fraction < 0)
			{
				fraction = 0;
			}
			if (fraction > 1)
			{
				fraction = 1;
			}
			m_progress.Progress = fraction;
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

		private void OnExpandTapped(object sender, EventArgs e)
		{
			m_mainView.OpenNowPlaying();
		}
	}
}

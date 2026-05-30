using System;
using Microsoft.Maui;
using Microsoft.Maui.ApplicationModel;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Pulse;
using Thump.Views;

namespace Thump.Views.Tiles
{
	public class TrackRowTile : ThumpView
	{
		private ArtImage m_art;
		private Label m_titleLabel;
		private Label m_artistLabel;
		private Label m_durationLabel;
		private Label m_cacheIcon;
		private PulseTrack m_song;
		private bool m_isPlayable = true;

		public TrackRowTile() : base(MainView.Self)
		{
			
		}

		protected override void BuildLayout()
		{
			Grid grid = new Grid();
			grid.Padding = new Thickness(16, 8);

			ColumnDefinition artColumn = new ColumnDefinition();
			artColumn.Width = new GridLength(44);
			ColumnDefinition textColumn = new ColumnDefinition();
			textColumn.Width = GridLength.Star;
			ColumnDefinition durationColumn = new ColumnDefinition();
			durationColumn.Width = GridLength.Auto;
			ColumnDefinition optionsColumn = new ColumnDefinition();
			optionsColumn.Width = GridLength.Auto;
			grid.ColumnDefinitions.Add(artColumn);
			grid.ColumnDefinitions.Add(textColumn);
			grid.ColumnDefinitions.Add(durationColumn);
			grid.ColumnDefinitions.Add(optionsColumn);

			grid.Children.Add(BuildArt());
			grid.Children.Add(BuildText());
			grid.Children.Add(BuildDuration());
			grid.Children.Add(BuildOptions());

			TapGestureRecognizer tap = new TapGestureRecognizer();
			tap.Tapped += OnTapped;
			grid.GestureRecognizers.Add(tap);

			Content = grid;
		}

		private View BuildArt()
		{
			Grid artGrid = new Grid();
			artGrid.WidthRequest = 44;
			artGrid.HeightRequest = 44;
			artGrid.VerticalOptions = LayoutOptions.Center;

			m_art = new ArtImage();
			m_art.WidthRequest = 44;
			m_art.HeightRequest = 44;
			artGrid.Children.Add(m_art);

			artGrid.Children.Add(BuildCacheIcon());

			Grid.SetColumn(artGrid, 0);
			return artGrid;
		}

		private View BuildText()
		{
			StackLayout textStack = new StackLayout();
			textStack.VerticalOptions = LayoutOptions.Center;
			textStack.Spacing = 2;
			textStack.Padding = new Thickness(12, 0, 0, 0);

			m_titleLabel = new Label();
			m_titleLabel.Text = "Track title";
			m_titleLabel.TextColor = ThumpColors.OnBackground;
			m_titleLabel.FontSize = 15;
			m_titleLabel.LineBreakMode = LineBreakMode.TailTruncation;
			textStack.Children.Add(m_titleLabel);

			m_artistLabel = new Label();
			m_artistLabel.Text = "Artist";
			m_artistLabel.TextColor = ThumpColors.TextSecondary;
			m_artistLabel.FontSize = 12;
			m_artistLabel.LineBreakMode = LineBreakMode.TailTruncation;
			textStack.Children.Add(m_artistLabel);

			Grid.SetColumn(textStack, 1);
			return textStack;
		}

		private View BuildDuration()
		{
			m_durationLabel = new Label();
			m_durationLabel.Text = "0:00";
			m_durationLabel.TextColor = ThumpColors.TextDim;
			m_durationLabel.FontSize = 12;
			m_durationLabel.VerticalOptions = LayoutOptions.Center;

			Grid.SetColumn(m_durationLabel, 2);
			return m_durationLabel;
		}

		private View BuildCacheIcon()
		{
			m_cacheIcon = new Label();
			m_cacheIcon.FontFamily = "MaterialIcons";
			m_cacheIcon.Text = "\uE837";
			m_cacheIcon.FontSize = 12;
			m_cacheIcon.TextColor = ThumpColors.Accent;
			m_cacheIcon.HorizontalOptions = LayoutOptions.End;
			m_cacheIcon.VerticalOptions = LayoutOptions.End;
			m_cacheIcon.Margin = new Thickness(0, 0, 2, 2);
			m_cacheIcon.IsVisible = false;

			return m_cacheIcon;
		}

		private View BuildOptions()
		{
			Button optionsButton = new Button();
			optionsButton.Text = "⋮";
			optionsButton.FontSize = 18;
			optionsButton.TextColor = ThumpColors.TextSecondary;
			optionsButton.BackgroundColor = Colors.Transparent;
			optionsButton.WidthRequest = 40;
			optionsButton.HeightRequest = 40;
			optionsButton.Padding = new Thickness(0);
			optionsButton.VerticalOptions = LayoutOptions.Center;
			optionsButton.Clicked += OnOptionsClicked;

			Grid.SetColumn(optionsButton, 3);
			return optionsButton;
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		protected override void OnBindingContextChanged()
		{
			base.OnBindingContextChanged();
			m_cacheIcon.IsVisible = false;
			m_titleLabel.Opacity = 1.0;
			m_artistLabel.Opacity = 1.0;
			m_isPlayable = true;
			PulseTrack song = BindingContext as PulseTrack;
			if (song == null)
			{
				return;
			}
			m_song = song;
			m_titleLabel.Text = song.Title;
			m_artistLabel.Text = song.Artist;
			m_durationLabel.Text = FormatDuration(song.Duration);
			m_art.SetCoverArt(song.ImageID);
			UpdateAvailability(song);
		}

		private void UpdateAvailability(PulseTrack song)
		{
			bool online = MainView.Data.Pulse.IsOnline();
			string id = song.Id;
			System.Threading.Tasks.Task.Run(() =>
			{
				bool cached = MainView.Data.IsTrackCached(song);
				MainThread.BeginInvokeOnMainThread(() =>
				{
					if (m_song == null || m_song.Id != id)
					{
						return;
					}
					m_cacheIcon.IsVisible = cached;
					double opacity;
					if (!online && !cached)
					{
						opacity = 0.4;
					}
					else
					{
						opacity = 1.0;
					}
					m_titleLabel.Opacity = opacity;
					m_artistLabel.Opacity = opacity;
					bool playable;
					if (!online && !cached)
					{
						playable = false;
					}
					else
					{
						playable = true;
					}
					m_isPlayable = playable;
				});
			});
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

		private void OnTapped(object sender, EventArgs e)
		{
			if (m_song == null)
			{
				return;
			}
			if (!m_isPlayable)
			{
				return;
			}
			m_mainView.OnTrackSelected(m_song);
		}

		private void OnOptionsClicked(object sender, EventArgs e)
		{
			if (m_song == null)
			{
				return;
			}
			m_mainView.OnTrackOptions(m_song);
		}
	}
}

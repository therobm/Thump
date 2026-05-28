using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Thump.Pulse;

namespace Thump.Views.Tiles
{
	public class TrackRowTile : ThumpView
	{
		private Label m_titleLabel;
		private Label m_artistLabel;
		private Label m_durationLabel;
		private PulseTrack m_song;

		public TrackRowTile() : base(MainView.Self)
		{
			
		}

		protected override void BuildLayout()
		{
			Grid grid = new Grid();
			grid.Padding = new Thickness(16, 8);

			ColumnDefinition textColumn = new ColumnDefinition();
			textColumn.Width = GridLength.Star;
			ColumnDefinition durationColumn = new ColumnDefinition();
			durationColumn.Width = GridLength.Auto;
			grid.ColumnDefinitions.Add(textColumn);
			grid.ColumnDefinitions.Add(durationColumn);

			grid.Children.Add(BuildText());
			grid.Children.Add(BuildDuration());

			TapGestureRecognizer tap = new TapGestureRecognizer();
			tap.Tapped += OnTapped;
			grid.GestureRecognizers.Add(tap);

			Content = grid;
		}

		private View BuildText()
		{
			StackLayout textStack = new StackLayout();
			textStack.VerticalOptions = LayoutOptions.Center;
			textStack.Spacing = 2;

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

			Grid.SetColumn(textStack, 0);
			return textStack;
		}

		private View BuildDuration()
		{
			m_durationLabel = new Label();
			m_durationLabel.Text = "0:00";
			m_durationLabel.TextColor = ThumpColors.TextDim;
			m_durationLabel.FontSize = 12;
			m_durationLabel.VerticalOptions = LayoutOptions.Center;

			Grid.SetColumn(m_durationLabel, 1);
			return m_durationLabel;
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		protected override void OnBindingContextChanged()
		{
			base.OnBindingContextChanged();
			PulseTrack song = BindingContext as PulseTrack;
			if (song == null)
			{
				return;
			}
			m_song = song;
			m_titleLabel.Text = song.Title;
			m_artistLabel.Text = song.Artist;
			m_durationLabel.Text = FormatDuration(song.Duration);
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
			m_mainView.OnTrackSelected(m_song);
		}
	}
}

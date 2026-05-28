using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Pulse;

namespace Thump.Views.Tiles
{
	public class QueueRowTile : ThumpView
	{
		private Grid m_rowGrid;
		private Label m_titleLabel;
		private Label m_artistLabel;
		private PulseTrack m_song;

		public QueueRowTile() : base(MainView.Self)
		{

		}

		protected override void BuildLayout()
		{
			m_rowGrid = new Grid();
			m_rowGrid.Padding = new Thickness(24, 10);

			ColumnDefinition textColumn = new ColumnDefinition();
			textColumn.Width = GridLength.Star;
			m_rowGrid.ColumnDefinitions.Add(textColumn);

			m_rowGrid.Children.Add(BuildText());

			TapGestureRecognizer tap = new TapGestureRecognizer();
			tap.Tapped += OnTapped;
			m_rowGrid.GestureRecognizers.Add(tap);

			Content = m_rowGrid;
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

			PulseTrack current = MainView.Self.GetCurrentTrack();
			bool isCurrent = current != null && current == song;
			if (isCurrent)
			{
				m_titleLabel.TextColor = ThumpColors.Accent;
				m_rowGrid.BackgroundColor = ThumpColors.Surface;
			}
			else
			{
				m_titleLabel.TextColor = ThumpColors.OnBackground;
				m_rowGrid.BackgroundColor = Colors.Transparent;
			}
		}

		private void OnTapped(object sender, EventArgs e)
		{
			if (m_song == null)
			{
				return;
			}
			m_mainView.OnQueueTrackSelected(m_song);
		}
	}
}

using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Controls.Shapes;
using Thump.Data;
using Thump.Pulse;
using Thump.Views;

namespace Thump.Views.Tiles
{
	public class HomeTopTile : ThumpView
	{
		private ArtImage m_art;
		private Label m_titleLabel;
		private ThumpDataOb m_item;

		public HomeTopTile() : base(MainView.Self)
		{

		}

		protected override void BuildLayout()
		{
			Grid grid = new Grid();
			grid.Padding = new Thickness(6);
			grid.ColumnSpacing = 10;

			ColumnDefinition artColumn = new ColumnDefinition();
			artColumn.Width = GridLength.Auto;
			ColumnDefinition textColumn = new ColumnDefinition();
			textColumn.Width = GridLength.Star;
			grid.ColumnDefinitions.Add(artColumn);
			grid.ColumnDefinitions.Add(textColumn);

			grid.Children.Add(BuildArt());
			grid.Children.Add(BuildLabel());

			Border panel = new Border();
			panel.StrokeThickness = 0;
			panel.BackgroundColor = ThumpColors.SurfaceElevated;
			RoundRectangle shape = new RoundRectangle();
			shape.CornerRadius = new CornerRadius(8);
			panel.StrokeShape = shape;
			panel.Content = grid;

			TapGestureRecognizer tap = new TapGestureRecognizer();
			tap.Tapped += OnTapped;
			panel.GestureRecognizers.Add(tap);

			Content = panel;
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

		private View BuildLabel()
		{
			m_titleLabel = new Label();
			m_titleLabel.Text = "Title";
			m_titleLabel.TextColor = ThumpColors.OnBackground;
			m_titleLabel.FontSize = 13;
			m_titleLabel.FontAttributes = FontAttributes.Bold;
			m_titleLabel.LineBreakMode = LineBreakMode.TailTruncation;
			m_titleLabel.MaxLines = 2;
			m_titleLabel.VerticalOptions = LayoutOptions.Center;

			Grid.SetColumn(m_titleLabel, 1);
			return m_titleLabel;
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		protected override void OnBindingContextChanged()
		{
			base.OnBindingContextChanged();
			ThumpDataOb item = BindingContext as ThumpDataOb;
			if (item == null)
			{
				return;
			}
			m_item = item;

			if (item.Kind == eDataType.Track)
			{
				PulseTrack song = item as PulseTrack;
				if (song != null)
				{
					m_art.SetShape(eArtShape.RoundedRect);
					m_titleLabel.Text = song.Title;
					m_art.SetCoverArt(song.ImageID);
				}
			}
			else if (item.Kind == eDataType.Album)
			{
				PulseAlbum album = item as PulseAlbum;
				if (album != null)
				{
					m_art.SetShape(eArtShape.RoundedRect);
					m_titleLabel.Text = album.Name;
					m_art.SetCoverArt(album.CoverArt);
				}
			}
			else if (item.Kind == eDataType.Playlist)
			{
				PulsePlaylist playlist = item as PulsePlaylist;
				if (playlist != null)
				{
					m_art.SetShape(eArtShape.RoundedRect);
					m_titleLabel.Text = playlist.Name;
					m_art.SetCoverArt(playlist.CoverArt);
				}
			}
			else if (item.Kind == eDataType.Artist)
			{
				PulseArtist artist = item as PulseArtist;
				if (artist != null)
				{
					m_art.SetShape(eArtShape.Circle);
					m_titleLabel.Text = artist.Name;
					m_art.SetCoverArt(artist.CoverArt);
				}
			}
		}

		private void OnTapped(object sender, EventArgs e)
		{
			if (m_item == null)
			{
				return;
			}
			m_mainView.OnHomeItemSelected(m_item);
		}
	}
}

using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Thump.Data;
using Thump.Pulse;
using Thump.Views;

namespace Thump.Views.Tiles
{
	public class HomeCarouselTile : ThumpView
	{
		private ArtImage m_art;
		private Label m_titleLabel;
		private Label m_subtitleLabel;
		private ThumpDataOb m_item;

		public HomeCarouselTile() : base(MainView.Self)
		{
			
		}

		protected override void BuildLayout()
		{
			WidthRequest = 140;

			StackLayout stack = new StackLayout();
			stack.Spacing = 6;
			stack.Padding = new Thickness(6, 0);

			stack.Children.Add(BuildArt());
			stack.Children.Add(BuildLabels());

			TapGestureRecognizer tap = new TapGestureRecognizer();
			tap.Tapped += OnTapped;
			stack.GestureRecognizers.Add(tap);

			Content = stack;
		}

		private View BuildArt()
		{
			m_art = new ArtImage();
			m_art.WidthRequest = 128;
			m_art.HeightRequest = 128;

			return m_art;
		}

		private View BuildLabels()
		{
			StackLayout labelStack = new StackLayout();
			labelStack.Spacing = 6;

			m_titleLabel = new Label();
			m_titleLabel.Text = "Title";
			m_titleLabel.TextColor = ThumpColors.OnBackground;
			m_titleLabel.FontSize = 13;
			m_titleLabel.LineBreakMode = LineBreakMode.TailTruncation;
			m_titleLabel.MaxLines = 1;
			labelStack.Children.Add(m_titleLabel);

			m_subtitleLabel = new Label();
			m_subtitleLabel.Text = "Subtitle";
			m_subtitleLabel.TextColor = ThumpColors.TextSecondary;
			m_subtitleLabel.FontSize = 11;
			m_subtitleLabel.LineBreakMode = LineBreakMode.TailTruncation;
			m_subtitleLabel.MaxLines = 1;
			labelStack.Children.Add(m_subtitleLabel);

			return labelStack;
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
					m_titleLabel.Text = song.Title;
					m_subtitleLabel.Text = song.Artist;
					m_art.SetCoverArt(song.ImageID);
				}
			}
			else if (item.Kind == eDataType.Album)
			{
				PulseAlbum album = item as PulseAlbum;
				if (album != null)
				{
					m_titleLabel.Text = album.Name;
					m_subtitleLabel.Text = album.Artist;
					m_art.SetCoverArt(album.CoverArt);
				}
			}
			else if (item.Kind == eDataType.Playlist)
			{
				PulsePlaylist playlist = item as PulsePlaylist;
				if (playlist != null)
				{
					m_titleLabel.Text = playlist.Name;
					m_subtitleLabel.Text = playlist.SongCount + " tracks";
					m_art.SetCoverArt(playlist.CoverArt);
				}
			}
			else if (item.Kind == eDataType.Artist)
			{
				PulseArtist artist = item as PulseArtist;
				if (artist != null)
				{
					m_titleLabel.Text = artist.Name;
					m_subtitleLabel.Text = artist.AlbumCount + " albums";
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

using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Thump.Pulse;
using Thump.Views;

namespace Thump.Views.Tiles
{
	public class AlbumRowTile : ThumpView
	{
		private ArtImage m_art;
		private Label m_nameLabel;
		private Label m_subtitleLabel;
		private PulseAlbum m_album;

		public AlbumRowTile() : base(MainView.Self)
		{
			
		}

		protected override void BuildLayout()
		{
			Grid grid = new Grid();
			grid.Padding = new Thickness(16, 8);

			ColumnDefinition artColumn = new ColumnDefinition();
			artColumn.Width = new GridLength(56);
			ColumnDefinition textColumn = new ColumnDefinition();
			textColumn.Width = GridLength.Star;
			grid.ColumnDefinitions.Add(artColumn);
			grid.ColumnDefinitions.Add(textColumn);

			grid.Children.Add(BuildArt());
			grid.Children.Add(BuildText());

			TapGestureRecognizer tap = new TapGestureRecognizer();
			tap.Tapped += OnTapped;
			grid.GestureRecognizers.Add(tap);

			Content = grid;
		}

		private View BuildArt()
		{
			m_art = new ArtImage();
			m_art.WidthRequest = 56;
			m_art.HeightRequest = 56;
			m_art.VerticalOptions = LayoutOptions.Center;

			Grid.SetColumn(m_art, 0);
			return m_art;
		}

		private View BuildText()
		{
			StackLayout textStack = new StackLayout();
			textStack.VerticalOptions = LayoutOptions.Center;
			textStack.Spacing = 2;
			textStack.Padding = new Thickness(12, 0, 0, 0);

			m_nameLabel = new Label();
			m_nameLabel.Text = "Album name";
			m_nameLabel.TextColor = ThumpColors.OnBackground;
			m_nameLabel.FontSize = 16;
			m_nameLabel.LineBreakMode = LineBreakMode.TailTruncation;
			textStack.Children.Add(m_nameLabel);

			m_subtitleLabel = new Label();
			m_subtitleLabel.Text = "Artist";
			m_subtitleLabel.TextColor = ThumpColors.TextSecondary;
			m_subtitleLabel.FontSize = 12;
			m_subtitleLabel.LineBreakMode = LineBreakMode.TailTruncation;
			textStack.Children.Add(m_subtitleLabel);

			Grid.SetColumn(textStack, 1);
			return textStack;
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		protected override void OnBindingContextChanged()
		{
			base.OnBindingContextChanged();
			PulseAlbum album = BindingContext as PulseAlbum;
			if (album == null)
			{
				return;
			}
			m_album = album;
			m_nameLabel.Text = album.Name;
			m_subtitleLabel.Text = album.Artist;
			m_art.SetCoverArt(album.CoverArt);
		}

		private void OnTapped(object sender, EventArgs e)
		{
			if (m_album == null)
			{
				return;
			}
			m_mainView.OnAlbumSelected(m_album);
		}
	}
}

using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Thump.Pulse;
using Thump.Views;

namespace Thump.Views.Tiles
{
	public class GenreRowTile : ThumpView
	{
		private Label m_nameLabel;
		private Label m_subtitleLabel;
		private PulseGenre m_genre;

		public GenreRowTile() : base(MainView.Self)
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
			BoxView art = new BoxView();
			art.WidthRequest = 56;
			art.HeightRequest = 56;
			art.CornerRadius = 6;
			art.Color = ThumpColors.PlaceholderArt;
			art.VerticalOptions = LayoutOptions.Center;

			Grid.SetColumn(art, 0);
			return art;
		}

		private View BuildText()
		{
			StackLayout textStack = new StackLayout();
			textStack.VerticalOptions = LayoutOptions.Center;
			textStack.Spacing = 2;
			textStack.Padding = new Thickness(12, 0, 0, 0);

			m_nameLabel = new Label();
			m_nameLabel.Text = "Genre";
			m_nameLabel.TextColor = ThumpColors.OnBackground;
			m_nameLabel.FontSize = 16;
			m_nameLabel.LineBreakMode = LineBreakMode.TailTruncation;
			textStack.Children.Add(m_nameLabel);

			m_subtitleLabel = new Label();
			m_subtitleLabel.Text = "";
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
			PulseGenre genre = BindingContext as PulseGenre;
			if (genre == null)
			{
				return;
			}
			m_genre = genre;
			m_nameLabel.Text = genre.Name;
			m_subtitleLabel.Text = genre.SongCount + " songs  ·  " + genre.AlbumCount + " albums";
		}

		private void OnTapped(object sender, EventArgs e)
		{
			if (m_genre == null)
			{
				return;
			}
			m_mainView.OnGenreSelected(m_genre);
		}
	}
}

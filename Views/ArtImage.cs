using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Controls.Shapes;

namespace Thump.Views
{
	public class ArtImage : ThumpView
	{
		private Border m_border;
		private Image m_image;

		public ArtImage() : base(MainView.Self)
		{
			
		}

		public ArtImage(MainView mainView) : base(mainView)
		{
			
		}

		protected override void BuildLayout()
		{
			m_image = new Image();
			m_image.Aspect = Aspect.AspectFill;
			m_image.IsVisible = false;

			m_border = new Border();
			m_border.StrokeThickness = 0;
			m_border.BackgroundColor = ThumpColors.PlaceholderArt;
			RoundRectangle shape = new RoundRectangle();
			shape.CornerRadius = new CornerRadius(6);
			m_border.StrokeShape = shape;
			m_border.Content = m_image;

			Content = m_border;
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		public void MakeCircular()
		{
			m_border.StrokeShape = new Ellipse();
		}

		public void SetCoverArt(string coverArtId)
		{
			if (string.IsNullOrEmpty(coverArtId))
			{
				m_image.IsVisible = false;
				return;
			}
			if (MainView.Data == null)
			{
				m_image.IsVisible = false;
				return;
			}
			MainView.Data.GetCoverArt(coverArtId, OnArtLoaded);
		}

		private void OnArtLoaded(byte[] data)
		{
			if (data == null || data.Length == 0)
			{
				return;
			}
			m_image.Source = ImageSource.FromStream(() => new System.IO.MemoryStream(data));
			m_image.IsVisible = true;
		}
	}
}

using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Controls.Shapes;

namespace Thump.Views
{
	public enum eArtShape
	{
		RoundedRect,
		Circle,
	}

	public class ArtImage : ThumpView
	{
		private const double s_cornerRadius = 10;

		private Border m_border;
		private Image m_image;
		private eArtShape m_shape = eArtShape.RoundedRect;

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
			m_border.Content = m_image;

			Content = m_border;

			ApplyShape();
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		public void SetShape(eArtShape shape)
		{
			m_shape = shape;
			ApplyShape();
		}

		public void SetAspect(Aspect aspect)
		{
			m_image.Aspect = aspect;
		}

		private void ApplyShape()
		{
			if (m_shape == eArtShape.Circle)
			{
				m_border.StrokeShape = new Ellipse();
			}
			else
			{
				RoundRectangle rounded = new RoundRectangle();
				rounded.CornerRadius = new CornerRadius(s_cornerRadius);
				m_border.StrokeShape = rounded;
			}
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

using Microsoft.Maui.Controls;

namespace Thump.Views
{
	public class ThumpView : ContentView
	{
		protected MainView m_mainView;

		public ThumpView(MainView mainView)
		{
			m_mainView = mainView;
            BuildLayout();
        }

        protected virtual void BuildLayout()
        {

        }
        
		public virtual void Initialize()
		{
		}

		

    }
}

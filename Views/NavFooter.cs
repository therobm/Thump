using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump;

namespace Thump.Views
{
	public class NavFooter : ThumpView
	{
		private static readonly Color s_activeColor = Color.FromArgb("#3b82f6");
		private static readonly Color s_inactiveColor = Color.FromArgb("#555568");

		private Button m_homeButton;
		private Button m_libraryButton;
		private Button m_searchButton;
		private Button m_settingsButton;

		public NavFooter(MainView mainView) : base(mainView)
		{
			
		}

		protected override void BuildLayout()
		{
			BackgroundColor = Color.FromArgb("#060606");
			HeightRequest = 72;

			Grid grid = new Grid();

			ColumnDefinition homeColumn = new ColumnDefinition();
			homeColumn.Width = GridLength.Star;
			ColumnDefinition libraryColumn = new ColumnDefinition();
			libraryColumn.Width = GridLength.Star;
			ColumnDefinition searchColumn = new ColumnDefinition();
			searchColumn.Width = GridLength.Star;
			ColumnDefinition settingsColumn = new ColumnDefinition();
			settingsColumn.Width = GridLength.Star;
			grid.ColumnDefinitions.Add(homeColumn);
			grid.ColumnDefinitions.Add(libraryColumn);
			grid.ColumnDefinitions.Add(searchColumn);
			grid.ColumnDefinitions.Add(settingsColumn);

			grid.Children.Add(BuildHomeTab());
			grid.Children.Add(BuildLibraryTab());
			grid.Children.Add(BuildSearchTab());
			grid.Children.Add(BuildSettingsTab());

			Content = grid;
		}

		private View BuildHomeTab()
		{
			m_homeButton = new Button();
			m_homeButton.Text = "Home";
			m_homeButton.TextColor = s_activeColor;
			m_homeButton.BackgroundColor = Colors.Transparent;
			m_homeButton.FontSize = 13;
			m_homeButton.Clicked += OnHomeClicked;

			Grid.SetColumn(m_homeButton, 0);
			return m_homeButton;
		}

		private View BuildLibraryTab()
		{
			m_libraryButton = new Button();
			m_libraryButton.Text = "Library";
			m_libraryButton.TextColor = s_inactiveColor;
			m_libraryButton.BackgroundColor = Colors.Transparent;
			m_libraryButton.FontSize = 13;
			m_libraryButton.Clicked += OnLibraryClicked;

			Grid.SetColumn(m_libraryButton, 1);
			return m_libraryButton;
		}

		private View BuildSearchTab()
		{
			m_searchButton = new Button();
			m_searchButton.Text = "Search";
			m_searchButton.TextColor = s_inactiveColor;
			m_searchButton.BackgroundColor = Colors.Transparent;
			m_searchButton.FontSize = 13;
			m_searchButton.Clicked += OnSearchClicked;

			Grid.SetColumn(m_searchButton, 2);
			return m_searchButton;
		}

		private View BuildSettingsTab()
		{
			m_settingsButton = new Button();
			m_settingsButton.Text = "Settings";
			m_settingsButton.TextColor = s_inactiveColor;
			m_settingsButton.BackgroundColor = Colors.Transparent;
			m_settingsButton.FontSize = 13;
			m_settingsButton.Clicked += OnSettingsClicked;

			Grid.SetColumn(m_settingsButton, 3);
			return m_settingsButton;
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		public void SetActiveTab(eTab tab)
		{
			m_homeButton.TextColor = s_inactiveColor;
			m_libraryButton.TextColor = s_inactiveColor;
			m_searchButton.TextColor = s_inactiveColor;
			m_settingsButton.TextColor = s_inactiveColor;

			if (tab == eTab.Home)
			{
				m_homeButton.TextColor = s_activeColor;
			}
			else if (tab == eTab.Library)
			{
				m_libraryButton.TextColor = s_activeColor;
			}
			else if (tab == eTab.Search)
			{
				m_searchButton.TextColor = s_activeColor;
			}
			else if (tab == eTab.Settings)
			{
				m_settingsButton.TextColor = s_activeColor;
			}
		}

		private void OnHomeClicked(object sender, EventArgs e)
		{
			m_mainView.NavigateToHome();
		}

		private void OnLibraryClicked(object sender, EventArgs e)
		{
			m_mainView.NavigateToLibrary();
		}

		private void OnSearchClicked(object sender, EventArgs e)
		{
			m_mainView.NavigateToSearch();
		}

		private void OnSettingsClicked(object sender, EventArgs e)
		{
			m_mainView.NavigateToSettings();
		}
	}
}

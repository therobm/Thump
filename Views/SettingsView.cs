using System;
using Microsoft.Maui;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;

namespace Thump.Views
{
	public class SettingsView : ThumpView
	{
		private Entry m_serverUrlEntry;
		private Entry m_usernameEntry;
		private Entry m_passwordEntry;
		private Entry m_prefetchEntry;
		private Entry m_cacheSizeEntry;
		private Switch m_scrobbleSwitch;

		public SettingsView(MainView mainView) : base(mainView)
		{
			
		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Background;

			ScrollView scroll = new ScrollView();

			StackLayout stack = new StackLayout();
			stack.Spacing = 16;
			stack.Padding = new Thickness(16, 12);

			stack.Children.Add(BuildTitle());
			stack.Children.Add(BuildServerFields());
			stack.Children.Add(BuildPrefetchField());
			stack.Children.Add(BuildCacheSizeField());
			stack.Children.Add(BuildScrobbleToggle());
			stack.Children.Add(BuildActionButtons());

			scroll.Content = stack;
			Content = scroll;
		}

		private View BuildTitle()
		{
			Label header = new Label();
			header.Text = "Settings";
			header.FontSize = 24;
			header.TextColor = ThumpColors.OnBackground;
			return header;
		}

		private View BuildServerFields()
		{
			StackLayout fieldStack = new StackLayout();
			fieldStack.Spacing = 16;

			Label serverUrlLabel = new Label();
			serverUrlLabel.Text = "Server URL";
			serverUrlLabel.FontSize = 13;
			serverUrlLabel.TextColor = ThumpColors.TextSecondary;
			serverUrlLabel.Padding = new Thickness(0, 8, 0, 0);
			fieldStack.Children.Add(serverUrlLabel);

			m_serverUrlEntry = new Entry();
			m_serverUrlEntry.Text = "https://192.168.5.5:32458";
			m_serverUrlEntry.TextColor = ThumpColors.OnBackground;
			m_serverUrlEntry.BackgroundColor = ThumpColors.Surface;
			m_serverUrlEntry.FontSize = 15;
			fieldStack.Children.Add(m_serverUrlEntry);

			Label usernameLabel = new Label();
			usernameLabel.Text = "Username";
			usernameLabel.FontSize = 13;
			usernameLabel.TextColor = ThumpColors.TextSecondary;
			usernameLabel.Padding = new Thickness(0, 8, 0, 0);
			fieldStack.Children.Add(usernameLabel);

			m_usernameEntry = new Entry();
			m_usernameEntry.Text = "Rob";
			m_usernameEntry.TextColor = ThumpColors.OnBackground;
			m_usernameEntry.BackgroundColor = ThumpColors.Surface;
			m_usernameEntry.FontSize = 15;
			fieldStack.Children.Add(m_usernameEntry);

			Label passwordLabel = new Label();
			passwordLabel.Text = "Password";
			passwordLabel.FontSize = 13;
			passwordLabel.TextColor = ThumpColors.TextSecondary;
			passwordLabel.Padding = new Thickness(0, 8, 0, 0);
			fieldStack.Children.Add(passwordLabel);

			m_passwordEntry = new Entry();
			m_passwordEntry.IsPassword = true;
			m_passwordEntry.TextColor = ThumpColors.OnBackground;
			m_passwordEntry.BackgroundColor = ThumpColors.Surface;
			m_passwordEntry.FontSize = 15;
			fieldStack.Children.Add(m_passwordEntry);

			return fieldStack;
		}

		private View BuildPrefetchField()
		{
			StackLayout fieldStack = new StackLayout();
			fieldStack.Spacing = 16;

			Label prefetchLabel = new Label();
			prefetchLabel.Text = "Prefetch limit";
			prefetchLabel.FontSize = 13;
			prefetchLabel.TextColor = ThumpColors.TextSecondary;
			prefetchLabel.Padding = new Thickness(0, 16, 0, 0);
			fieldStack.Children.Add(prefetchLabel);

			m_prefetchEntry = new Entry();
			m_prefetchEntry.Text = "10";
			m_prefetchEntry.Keyboard = Keyboard.Numeric;
			m_prefetchEntry.TextColor = ThumpColors.OnBackground;
			m_prefetchEntry.BackgroundColor = ThumpColors.Surface;
			m_prefetchEntry.FontSize = 15;
			fieldStack.Children.Add(m_prefetchEntry);

			return fieldStack;
		}

		private View BuildCacheSizeField()
		{
			StackLayout fieldStack = new StackLayout();
			fieldStack.Spacing = 16;

			Label cacheSizeLabel = new Label();
			cacheSizeLabel.Text = "Audio cache size (MB)";
			cacheSizeLabel.FontSize = 13;
			cacheSizeLabel.TextColor = ThumpColors.TextSecondary;
			cacheSizeLabel.Padding = new Thickness(0, 8, 0, 0);
			fieldStack.Children.Add(cacheSizeLabel);

			m_cacheSizeEntry = new Entry();
			m_cacheSizeEntry.Text = "500";
			m_cacheSizeEntry.Keyboard = Keyboard.Numeric;
			m_cacheSizeEntry.TextColor = ThumpColors.OnBackground;
			m_cacheSizeEntry.BackgroundColor = ThumpColors.Surface;
			m_cacheSizeEntry.FontSize = 15;
			fieldStack.Children.Add(m_cacheSizeEntry);

			return fieldStack;
		}

		private View BuildScrobbleToggle()
		{
			HorizontalStackLayout scrobbleStack = new HorizontalStackLayout();
			scrobbleStack.Spacing = 12;
			scrobbleStack.Padding = new Thickness(0, 12, 0, 0);

			Label scrobbleLabel = new Label();
			scrobbleLabel.Text = "Scrobble";
			scrobbleLabel.TextColor = ThumpColors.OnBackground;
			scrobbleLabel.FontSize = 15;
			scrobbleLabel.VerticalOptions = LayoutOptions.Center;
			scrobbleStack.Children.Add(scrobbleLabel);

			m_scrobbleSwitch = new Switch();
			m_scrobbleSwitch.IsToggled = true;
			scrobbleStack.Children.Add(m_scrobbleSwitch);

			return scrobbleStack;
		}

		private View BuildActionButtons()
		{
			StackLayout buttonStack = new StackLayout();
			buttonStack.Spacing = 16;

			Button saveButton = new Button();
			saveButton.Text = "Save";
			saveButton.TextColor = ThumpColors.Background;
			saveButton.BackgroundColor = ThumpColors.Accent;
			saveButton.CornerRadius = 8;
			saveButton.FontSize = 15;
			saveButton.HeightRequest = 44;
			saveButton.Margin = new Thickness(0, 16, 0, 0);
			saveButton.Clicked += OnSaveClicked;
			buttonStack.Children.Add(saveButton);

			Button clearCacheButton = new Button();
			clearCacheButton.Text = "Clear cache";
			clearCacheButton.TextColor = ThumpColors.OnBackground;
			clearCacheButton.BackgroundColor = ThumpColors.Surface;
			clearCacheButton.CornerRadius = 8;
			clearCacheButton.FontSize = 15;
			clearCacheButton.HeightRequest = 44;
			clearCacheButton.Clicked += OnClearCacheClicked;
			buttonStack.Children.Add(clearCacheButton);

			return buttonStack;
		}

		public override void Initialize()
		{
			base.Initialize();
		}

		private void OnSaveClicked(object sender, EventArgs e)
		{
		}

		private void OnClearCacheClicked(object sender, EventArgs e)
		{
		}
	}
}

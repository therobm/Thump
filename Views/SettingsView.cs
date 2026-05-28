using System;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.Maui;
using Microsoft.Maui.ApplicationModel;
using Microsoft.Maui.Controls;
using Microsoft.Maui.Graphics;
using Thump.Data;
using Thump.Pulse;
using Thump.Utility;

namespace Thump.Views
{
	public class SettingsView : ThumpView
	{
		private static readonly Color s_successColor = Color.FromArgb("#3ddc84");
		private static readonly Color s_failColor = Color.FromArgb("#ef4444");

		private Switch m_scrobbleSwitch;
		private Button m_normalizeOff;
		private Button m_normalizePerTrack;
		private Button m_normalizePerAlbum;
		private eNormalizeVolume m_normalize = eNormalizeVolume.Off;

		private Slider m_prefetchSlider;
		private Label m_prefetchValueLabel;
		private Slider m_cacheSizeSlider;
		private Label m_cacheSizeValueLabel;
		private ProgressBar m_usageBar;
		private Label m_usageLabel;
		private Label m_tracksCachedLabel;
		private Label m_coverArtLabel;
		private Label m_oldestLabel;

		private Entry m_serverIpEntry;
		private Entry m_serverPortEntry;
		private Entry m_usernameEntry;
		private Entry m_passwordEntry;
		private Button m_authToken;
		private Button m_authLegacy;
		private PulseClient.eSubSonicAuthType m_authType = PulseClient.eSubSonicAuthType.Token;
		private Label m_connectStatusLabel;

		public SettingsView(MainView mainView) : base(mainView)
		{

		}

		protected override void BuildLayout()
		{
			BackgroundColor = ThumpColors.Background;

			ScrollView scroll = new ScrollView();

			VerticalStackLayout stack = new VerticalStackLayout();
			stack.Spacing = 20;
			stack.Padding = new Thickness(16, 12);

			stack.Children.Add(BuildTitle());
			stack.Children.Add(BuildPlaybackSection());
			stack.Children.Add(BuildCachingSection());
			stack.Children.Add(BuildLoginSection());

			scroll.Content = stack;
			Content = scroll;
		}

		private View BuildTitle()
		{
			Label header = new Label();
			header.Text = "Settings";
			header.FontSize = 24;
			header.FontAttributes = FontAttributes.Bold;
			header.TextColor = ThumpColors.OnBackground;
			return header;
		}

		private Label BuildSectionHeader(string text)
		{
			Label header = new Label();
			header.Text = text;
			header.FontSize = 13;
			header.FontAttributes = FontAttributes.Bold;
			header.TextColor = ThumpColors.Accent;
			header.Margin = new Thickness(0, 8, 0, 0);
			return header;
		}

		private Label BuildFieldLabel(string text)
		{
			Label label = new Label();
			label.Text = text;
			label.FontSize = 13;
			label.TextColor = ThumpColors.TextSecondary;
			return label;
		}

		private Entry BuildEntry()
		{
			Entry entry = new Entry();
			entry.TextColor = ThumpColors.OnBackground;
			entry.BackgroundColor = ThumpColors.Surface;
			entry.FontSize = 15;
			return entry;
		}

		private Button BuildSegmentButton(string text)
		{
			Button button = new Button();
			button.Text = text;
			button.TextColor = ThumpColors.OnBackground;
			button.BackgroundColor = ThumpColors.Surface;
			button.CornerRadius = 8;
			button.FontSize = 13;
			button.Padding = new Thickness(14, 4);
			button.HeightRequest = 36;
			return button;
		}

		private View BuildPlaybackSection()
		{
			VerticalStackLayout section = new VerticalStackLayout();
			section.Spacing = 12;
			section.Children.Add(BuildSectionHeader("Playback"));

			HorizontalStackLayout scrobbleRow = new HorizontalStackLayout();
			scrobbleRow.Spacing = 12;

			Label scrobbleLabel = new Label();
			scrobbleLabel.Text = "Enable Scrobble";
			scrobbleLabel.TextColor = ThumpColors.OnBackground;
			scrobbleLabel.FontSize = 15;
			scrobbleLabel.VerticalOptions = LayoutOptions.Center;
			scrobbleRow.Children.Add(scrobbleLabel);

			m_scrobbleSwitch = new Switch();
			m_scrobbleSwitch.IsToggled = true;
			m_scrobbleSwitch.Toggled += OnScrobbleToggled;
			scrobbleRow.Children.Add(m_scrobbleSwitch);
			section.Children.Add(scrobbleRow);

			section.Children.Add(BuildFieldLabel("Normalize Volume"));

			HorizontalStackLayout normalizeRow = new HorizontalStackLayout();
			normalizeRow.Spacing = 8;

			m_normalizeOff = BuildSegmentButton("Off");
			m_normalizeOff.Clicked += OnNormalizeOffClicked;
			normalizeRow.Children.Add(m_normalizeOff);

			m_normalizePerTrack = BuildSegmentButton("Per Track");
			m_normalizePerTrack.Clicked += OnNormalizePerTrackClicked;
			normalizeRow.Children.Add(m_normalizePerTrack);

			m_normalizePerAlbum = BuildSegmentButton("Per Album");
			m_normalizePerAlbum.Clicked += OnNormalizePerAlbumClicked;
			normalizeRow.Children.Add(m_normalizePerAlbum);

			section.Children.Add(normalizeRow);
			return section;
		}

		private View BuildCachingSection()
		{
			VerticalStackLayout section = new VerticalStackLayout();
			section.Spacing = 12;
			section.Children.Add(BuildSectionHeader("Caching"));

			m_prefetchValueLabel = BuildFieldLabel("Prefetch Tracks: 10");
			section.Children.Add(m_prefetchValueLabel);

			m_prefetchSlider = new Slider();
			m_prefetchSlider.Minimum = 0;
			m_prefetchSlider.Maximum = 30;
			m_prefetchSlider.ValueChanged += OnPrefetchChanged;
			section.Children.Add(m_prefetchSlider);

			m_cacheSizeValueLabel = BuildFieldLabel("Cache Size: 500 MB");
			section.Children.Add(m_cacheSizeValueLabel);

			m_cacheSizeSlider = new Slider();
			m_cacheSizeSlider.Minimum = 0;
			m_cacheSizeSlider.Maximum = 5120;
			m_cacheSizeSlider.ValueChanged += OnCacheSizeChanged;
			section.Children.Add(m_cacheSizeSlider);

			m_usageLabel = BuildFieldLabel("Cache usage: 0 B / 500 MB");
			section.Children.Add(m_usageLabel);

			m_usageBar = new ProgressBar();
			m_usageBar.ProgressColor = ThumpColors.Accent;
			m_usageBar.Progress = 0;
			section.Children.Add(m_usageBar);

			m_tracksCachedLabel = BuildFieldLabel("Tracks Cached: 0");
			section.Children.Add(m_tracksCachedLabel);

			m_coverArtLabel = BuildFieldLabel("Cover Art: 0");
			section.Children.Add(m_coverArtLabel);

			m_oldestLabel = BuildFieldLabel("Oldest Cached Object: —");
			section.Children.Add(m_oldestLabel);

			Button clearButton = new Button();
			clearButton.Text = "Clear Cache";
			clearButton.TextColor = ThumpColors.OnBackground;
			clearButton.BackgroundColor = ThumpColors.Surface;
			clearButton.CornerRadius = 8;
			clearButton.FontSize = 15;
			clearButton.HeightRequest = 44;
			clearButton.Margin = new Thickness(0, 4, 0, 0);
			clearButton.Clicked += OnClearCacheClicked;
			section.Children.Add(clearButton);

			return section;
		}

		private View BuildLoginSection()
		{
			VerticalStackLayout section = new VerticalStackLayout();
			section.Spacing = 12;
			section.Children.Add(BuildSectionHeader("Login"));

			section.Children.Add(BuildFieldLabel("Server IP"));
			m_serverIpEntry = BuildEntry();
			section.Children.Add(m_serverIpEntry);

			section.Children.Add(BuildFieldLabel("Server Port"));
			m_serverPortEntry = BuildEntry();
			m_serverPortEntry.Keyboard = Keyboard.Numeric;
			section.Children.Add(m_serverPortEntry);

			section.Children.Add(BuildFieldLabel("Username"));
			m_usernameEntry = BuildEntry();
			section.Children.Add(m_usernameEntry);

			section.Children.Add(BuildFieldLabel("Password"));
			m_passwordEntry = BuildEntry();
			m_passwordEntry.IsPassword = true;
			section.Children.Add(m_passwordEntry);

			section.Children.Add(BuildFieldLabel("Auth Type"));
			HorizontalStackLayout authRow = new HorizontalStackLayout();
			authRow.Spacing = 8;

			m_authToken = BuildSegmentButton("Token");
			m_authToken.Clicked += OnAuthTokenClicked;
			authRow.Children.Add(m_authToken);

			m_authLegacy = BuildSegmentButton("Legacy");
			m_authLegacy.Clicked += OnAuthLegacyClicked;
			authRow.Children.Add(m_authLegacy);
			section.Children.Add(authRow);

			Button connectButton = new Button();
			connectButton.Text = "Connect";
			connectButton.TextColor = ThumpColors.Background;
			connectButton.BackgroundColor = ThumpColors.Accent;
			connectButton.CornerRadius = 8;
			connectButton.FontSize = 15;
			connectButton.HeightRequest = 44;
			connectButton.Margin = new Thickness(0, 4, 0, 0);
			connectButton.Clicked += OnConnectClicked;
			section.Children.Add(connectButton);

			m_connectStatusLabel = new Label();
			m_connectStatusLabel.Text = "";
			m_connectStatusLabel.FontSize = 13;
			m_connectStatusLabel.TextColor = ThumpColors.TextSecondary;
			section.Children.Add(m_connectStatusLabel);

			return section;
		}

		public override void Initialize()
		{
			base.Initialize();

			m_scrobbleSwitch.IsToggled = ThumpSettings.GetScrobbleEnabled();
			SetNormalize(ThumpSettings.GetNormalizeVolume());

			int prefetch = ThumpSettings.GetPrefetchCount();
			m_prefetchSlider.Value = prefetch;
			m_prefetchValueLabel.Text = "Prefetch Tracks: " + prefetch;

			long limitBytes = ThumpSettings.GetCacheLimitBytes();
			int limitMb = (int)(limitBytes / (1024L * 1024L));
			m_cacheSizeSlider.Value = limitMb;
			m_cacheSizeValueLabel.Text = "Cache Size: " + FormatBytes(limitBytes);
			MainView.Self.GetCache().SetSizeLimitBytes(limitBytes);

			m_serverIpEntry.Text = ThumpSettings.GetServerIp();
			m_serverPortEntry.Text = ThumpSettings.GetServerPort();
			m_usernameEntry.Text = ThumpSettings.GetUsername();
			m_passwordEntry.Text = ThumpSettings.GetPassword();
			SetAuthType(ThumpSettings.GetAuthType());

			RefreshCacheStats();
		}

		private void OnScrobbleToggled(object sender, ToggledEventArgs e)
		{
			ThumpSettings.SetScrobbleEnabled(e.Value);
		}

		private void OnNormalizeOffClicked(object sender, EventArgs e)
		{
			SetNormalize(eNormalizeVolume.Off);
			ThumpSettings.SetNormalizeVolume(m_normalize);
		}

		private void OnNormalizePerTrackClicked(object sender, EventArgs e)
		{
			SetNormalize(eNormalizeVolume.PerTrack);
			ThumpSettings.SetNormalizeVolume(m_normalize);
		}

		private void OnNormalizePerAlbumClicked(object sender, EventArgs e)
		{
			SetNormalize(eNormalizeVolume.PerAlbum);
			ThumpSettings.SetNormalizeVolume(m_normalize);
		}

		private void SetNormalize(eNormalizeVolume value)
		{
			m_normalize = value;
			StyleSegment(m_normalizeOff, value == eNormalizeVolume.Off);
			StyleSegment(m_normalizePerTrack, value == eNormalizeVolume.PerTrack);
			StyleSegment(m_normalizePerAlbum, value == eNormalizeVolume.PerAlbum);
		}

		private void OnAuthTokenClicked(object sender, EventArgs e)
		{
			SetAuthType(PulseClient.eSubSonicAuthType.Token);
			ThumpSettings.SetAuthType(m_authType);
		}

		private void OnAuthLegacyClicked(object sender, EventArgs e)
		{
			SetAuthType(PulseClient.eSubSonicAuthType.Legacy);
			ThumpSettings.SetAuthType(m_authType);
		}

		private void SetAuthType(PulseClient.eSubSonicAuthType value)
		{
			m_authType = value;
			StyleSegment(m_authToken, value == PulseClient.eSubSonicAuthType.Token);
			StyleSegment(m_authLegacy, value == PulseClient.eSubSonicAuthType.Legacy);
		}

		private void StyleSegment(Button button, bool active)
		{
			if (active)
			{
				button.BackgroundColor = ThumpColors.Accent;
				button.TextColor = ThumpColors.Background;
			}
			else
			{
				button.BackgroundColor = ThumpColors.Surface;
				button.TextColor = ThumpColors.OnBackground;
			}
		}

		private void OnPrefetchChanged(object sender, ValueChangedEventArgs e)
		{
			int count = (int)Math.Round(e.NewValue);
			m_prefetchValueLabel.Text = "Prefetch Tracks: " + count;
			ThumpSettings.SetPrefetchCount(count);
		}

		private void OnCacheSizeChanged(object sender, ValueChangedEventArgs e)
		{
			int mb = (int)Math.Round(e.NewValue);
			long bytes = (long)mb * 1024L * 1024L;
			m_cacheSizeValueLabel.Text = "Cache Size: " + FormatBytes(bytes);
			ThumpSettings.SetCacheLimitBytes(bytes);
			MainView.Self.GetCache().SetSizeLimitBytes(bytes);
		}

		private void OnClearCacheClicked(object sender, EventArgs e)
		{
			ThumpCache cache = MainView.Self.GetCache();
			cache.Enqueue(() =>
			{
				cache.ClearCache();
			});
			RefreshCacheStats();
		}

		private static string ValidateAndNormalizeServer(string ip, string port, out string normalizedIp)
		{
			normalizedIp = "";
			if (string.IsNullOrWhiteSpace(ip))
			{
				return "Server IP is required.";
			}
			if (string.IsNullOrWhiteSpace(port))
			{
				return "Server port is required.";
			}
			int portNumber;
			if (!int.TryParse(port.Trim(), out portNumber) || portNumber < 1 || portNumber > 65535)
			{
				return "Server port must be a number between 1 and 65535.";
			}
			string host = ip.Trim();
			if (host.IndexOf(' ') >= 0)
			{
				return "Server IP cannot contain spaces.";
			}
			if (!host.StartsWith("http://") && !host.StartsWith("https://"))
			{
				host = "https://" + host;
			}
			Uri parsed;
			if (!Uri.TryCreate(host + ":" + portNumber, UriKind.Absolute, out parsed))
			{
				return "Server IP is not a valid address.";
			}
			if (parsed.Scheme != "http" && parsed.Scheme != "https")
			{
				return "Server address must be http or https.";
			}
			if (string.IsNullOrEmpty(parsed.Host))
			{
				return "Server IP is not a valid address.";
			}
			normalizedIp = host;
			return "";
		}

		private void OnConnectClicked(object sender, EventArgs e)
		{
			string ip = m_serverIpEntry.Text;
			string port = m_serverPortEntry.Text;
			string user = m_usernameEntry.Text;
			string password = m_passwordEntry.Text;

			string normalizedIp;
			string validationError = ValidateAndNormalizeServer(ip, port, out normalizedIp);
			if (!string.IsNullOrEmpty(validationError))
			{
				m_connectStatusLabel.Text = validationError;
				m_connectStatusLabel.TextColor = s_failColor;
				return;
			}
			ip = normalizedIp;

			ThumpSettings.SetServerIp(ip);
			ThumpSettings.SetServerPort(port);
			ThumpSettings.SetUsername(user);
			ThumpSettings.SetPassword(password);
			ThumpSettings.SetAuthType(m_authType);

			m_connectStatusLabel.Text = "Connecting…";
			m_connectStatusLabel.TextColor = ThumpColors.TextSecondary;

			PulseClient pulse = MainView.Data.Pulse;
			PulseClient.eSubSonicAuthType authType = m_authType;
			Task.Run(() =>
			{
				pulse.SetServerParams(ip, port, user, password, authType, true);
				bool success = pulse.TestConnection(out JsonElement response);
				string message = "Unknown";
				if (!success && response.TryGetProperty("error", out JsonElement error))
				{
					message = JsonHelper.GetString(error, "message");
				}
				bool capturedSuccess = success;
				string capturedMessage = message;
				MainThread.BeginInvokeOnMainThread(() =>
				{
					if (capturedSuccess)
					{
						m_connectStatusLabel.Text = "Connected";
						m_connectStatusLabel.TextColor = s_successColor;
					}
					else
					{
						m_connectStatusLabel.Text = "Failed: " + capturedMessage;
						m_connectStatusLabel.TextColor = s_failColor;
					}
				});
			});
		}

		private void RefreshCacheStats()
		{
			ThumpCache cache = MainView.Self.GetCache();
			cache.Enqueue(() =>
			{
				ThumpCacheStats stats = cache.GetCacheStats();
				MainThread.BeginInvokeOnMainThread(() =>
				{
					ApplyCacheStats(stats);
				});
			});
		}

		private void ApplyCacheStats(ThumpCacheStats stats)
		{
			long limitBytes = ThumpSettings.GetCacheLimitBytes();
			m_usageLabel.Text = "Cache usage: " + FormatBytes(stats.BytesUsed) + " / " + FormatBytes(limitBytes);
			if (limitBytes > 0)
			{
				double progress = (double)stats.BytesUsed / (double)limitBytes;
				if (progress > 1)
				{
					progress = 1;
				}
				m_usageBar.Progress = progress;
			}
			else
			{
				m_usageBar.Progress = 0;
			}
			m_tracksCachedLabel.Text = "Tracks Cached: " + stats.TrackCount;
			m_coverArtLabel.Text = "Cover Art: " + stats.CoverArtCount;
			m_oldestLabel.Text = "Oldest Cached Object: " + FormatAge(stats.OldestFetchedUnix);
		}

		private static string FormatBytes(long bytes)
		{
			if (bytes >= 1024L * 1024L * 1024L)
			{
				double gb = (double)bytes / (1024.0 * 1024.0 * 1024.0);
				return gb.ToString("0.0") + " GB";
			}
			if (bytes >= 1024L * 1024L)
			{
				long mb = bytes / (1024L * 1024L);
				return mb + " MB";
			}
			if (bytes >= 1024L)
			{
				long kb = bytes / 1024L;
				return kb + " KB";
			}
			return bytes + " B";
		}

		private static string FormatAge(long fetchedUnix)
		{
			if (fetchedUnix <= 0)
			{
				return "—";
			}
			long nowUnix = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
			long ageSeconds = nowUnix - fetchedUnix;
			if (ageSeconds < 0)
			{
				ageSeconds = 0;
			}
			long days = ageSeconds / 86400;
			long hours = (ageSeconds % 86400) / 3600;
			long minutes = (ageSeconds % 3600) / 60;
			return days + "d " + hours + "h " + minutes + "m";
		}
	}
}

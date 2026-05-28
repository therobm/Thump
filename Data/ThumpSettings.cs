using Microsoft.Maui.Storage;
using Thump.Pulse;

namespace Thump.Data
{
	public enum eNormalizeVolume
	{
		Off,
		PerTrack,
		PerAlbum,
	}

	public static class ThumpSettings
	{
		private const string s_keyScrobble = "thump.playback.scrobble";
		private const string s_keyNormalize = "thump.playback.normalize";
		private const string s_keyPrefetchCount = "thump.cache.prefetch";
		private const string s_keyCacheLimitBytes = "thump.cache.limitBytes";
		private const string s_keyServerIp = "thump.login.ip";
		private const string s_keyServerPort = "thump.login.port";
		private const string s_keyUsername = "thump.login.username";
		private const string s_keyPassword = "thump.login.password";
		private const string s_keyAuthType = "thump.login.authType";

		public static bool GetScrobbleEnabled()
		{
			return Preferences.Get(s_keyScrobble, true);
		}
		public static void SetScrobbleEnabled(bool value)
		{
			Preferences.Set(s_keyScrobble, value);
		}

		public static eNormalizeVolume GetNormalizeVolume()
		{
			int stored = Preferences.Get(s_keyNormalize, (int)eNormalizeVolume.Off);
			return (eNormalizeVolume)stored;
		}
		public static void SetNormalizeVolume(eNormalizeVolume value)
		{
			Preferences.Set(s_keyNormalize, (int)value);
		}

		public static int GetPrefetchCount()
		{
			return Preferences.Get(s_keyPrefetchCount, 10);
		}
		public static void SetPrefetchCount(int value)
		{
			Preferences.Set(s_keyPrefetchCount, value);
		}

		public static long GetCacheLimitBytes()
		{
			return Preferences.Get(s_keyCacheLimitBytes, 500L * 1024L * 1024L);
		}
		public static void SetCacheLimitBytes(long value)
		{
			Preferences.Set(s_keyCacheLimitBytes, value);
		}

		public static string GetServerIp()
		{
			return Preferences.Get(s_keyServerIp, "https://192.168.5.5");
		}
		public static void SetServerIp(string value)
		{
			Preferences.Set(s_keyServerIp, value);
		}

		public static string GetServerPort()
		{
			return Preferences.Get(s_keyServerPort, "32458");
		}
		public static void SetServerPort(string value)
		{
			Preferences.Set(s_keyServerPort, value);
		}

		public static string GetUsername()
		{
			return Preferences.Get(s_keyUsername, "Rob");
		}
		public static void SetUsername(string value)
		{
			Preferences.Set(s_keyUsername, value);
		}

		// TODO: move the password to SecureStorage before shipping; Preferences is plaintext.
		public static string GetPassword()
		{
			return Preferences.Get(s_keyPassword, "");
		}
		public static void SetPassword(string value)
		{
			Preferences.Set(s_keyPassword, value);
		}

		public static PulseClient.eSubSonicAuthType GetAuthType()
		{
			int stored = Preferences.Get(s_keyAuthType, (int)PulseClient.eSubSonicAuthType.Token);
			return (PulseClient.eSubSonicAuthType)stored;
		}
		public static void SetAuthType(PulseClient.eSubSonicAuthType value)
		{
			Preferences.Set(s_keyAuthType, (int)value);
		}
	}
}

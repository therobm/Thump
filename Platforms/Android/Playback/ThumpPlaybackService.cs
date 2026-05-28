using Android.App;
using Android.Content;
using Android.Content.PM;
using AndroidX.Media3.Common;
using AndroidX.Media3.ExoPlayer;
using AndroidX.Media3.Session;
using Thump.Data;

namespace Thump.Playback
{
	[Service(Exported = true, Enabled = true, Name = "com.companyname.thump.ThumpPlaybackService", ForegroundServiceType = ForegroundService.TypeMediaPlayback)]
	[IntentFilter(new string[] { "androidx.media3.session.MediaLibraryService", "android.media.browse.MediaBrowserService" })]
	public class ThumpPlaybackService : MediaLibraryService
	{
		/// <summary>
		/// A special sneaky global so the media service can access our data
		/// </summary>
		public static ThumpData s_ThumpData;

		private IExoPlayer m_player;
		private MediaLibraryService.MediaLibrarySession m_session;

		public override void OnCreate()
		{
			base.OnCreate();

			ExoPlayerBuilder builder = new ExoPlayerBuilder(this);
			builder.SetHandleAudioBecomingNoisy(true);
			m_player = builder.Build();

			MediaLibraryService.MediaLibrarySession.Builder sessionBuilder = new MediaLibraryService.MediaLibrarySession.Builder(this, m_player, new ThumpLibraryCallback(s_ThumpData));
			PendingIntent sessionActivity = BuildSessionActivity();
			if (sessionActivity != null)
			{
				sessionBuilder.SetSessionActivity(sessionActivity);
			}
			m_session = sessionBuilder.Build();
		}

		private PendingIntent BuildSessionActivity()
		{
			Intent intent = PackageManager.GetLaunchIntentForPackage(PackageName);
			if (intent == null)
			{
				return null;
			}
			return PendingIntent.GetActivity(this, 0, intent, PendingIntentFlags.Immutable | PendingIntentFlags.UpdateCurrent);
		}

		public override MediaLibrarySession OnGetSessionFromMediaLibraryService(MediaSession.ControllerInfo p0)
		{
			return m_session;
		}
		public override MediaLibraryService.MediaLibrarySession OnGetSession(MediaSession.ControllerInfo controllerInfo)
		{
			return m_session;
		}

		public override void OnTaskRemoved(Intent rootIntent)
		{
			bool keepRunning = false;
			if (m_player != null && m_player.PlayWhenReady && m_player.MediaItemCount > 0)
			{
				keepRunning = true;
			}
			if (!keepRunning)
			{
				StopSelf();
			}
			base.OnTaskRemoved(rootIntent);
		}

		public override void OnDestroy()
		{
			if (m_session != null)
			{
				m_session.Release();
				m_session = null;
			}
			if (m_player != null)
			{
				m_player.Release();
				m_player = null;
			}
			base.OnDestroy();
		}
	}
}

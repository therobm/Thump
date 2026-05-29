using System.Collections.Generic;
using System.Timers;
using AndroidX.Media3.Common;
using AndroidX.Media3.ExoPlayer;
using Microsoft.Maui.ApplicationModel;
using Thump.Data;
using Thump.Pulse;

namespace Thump.Playback
{
	public class QueuePrefetcher
	{
		private const int s_window = 10;
		private const double s_pumpIntervalMs = 750;

		private IExoPlayer m_player;
		private ThumpData m_data;
		private Timer m_timer;

		private List<PulseTrack> m_tracks;
		private int m_startIndex;
		private int m_addedThrough;
		private int m_generation;
		private bool m_fetching;

		public QueuePrefetcher(IExoPlayer player, ThumpData data)
		{
			m_player = player;
			m_data = data;

			m_timer = new Timer(s_pumpIntervalMs);
			m_timer.AutoReset = true;
			m_timer.Elapsed += OnTimerElapsed;
		}

		public void LoadCollection(List<PulseTrack> tracks, int startIndex, System.Action<MediaItem> onStartResolved)
		{
			// Completing onStartResolved(null) here keeps the Android Auto future from hanging on an empty collection.
			if (tracks == null || tracks.Count == 0 || startIndex < 0 || startIndex >= tracks.Count)
			{
				if (onStartResolved != null)
				{
					onStartResolved(null);
				}
				return;
			}

			m_generation = m_generation + 1;
			m_tracks = tracks;
			m_startIndex = startIndex;
			m_addedThrough = startIndex;
			m_fetching = false;

			int generation = m_generation;
			PulseTrack startTrack = tracks[startIndex];
			m_data.GetTrackAudioFile(startTrack, (localPath) =>
			{
				if (generation != m_generation)
				{
					return;
				}
				if (string.IsNullOrEmpty(localPath))
				{
					if (onStartResolved != null)
					{
						onStartResolved(null);
					}
					return;
				}
				MediaItem item = BuildItem(startTrack, localPath);
				if (onStartResolved != null)
				{
					onStartResolved(item);
				}
				m_timer.Start();
			});
		}

		private void OnTimerElapsed(object sender, ElapsedEventArgs e)
		{
			MainThread.BeginInvokeOnMainThread(() =>
			{
				Pump();
			});
		}

		private void Pump()
		{
			if (m_player == null || m_tracks == null || m_tracks.Count == 0 || m_fetching)
			{
				return;
			}

			MediaItem current = m_player.CurrentMediaItem;
			if (current == null)
			{
				return;
			}

			int currentIndex = -1;
			for (int idx = 0; idx < m_tracks.Count; idx++)
			{
				if (m_tracks[idx].Id == current.MediaId)
				{
					currentIndex = idx;
					break;
				}
			}
			// The player is on a track this prefetcher did not load (e.g. the in-app player owns the queue); never touch it.
			if (currentIndex < 0)
			{
				return;
			}

			int target = currentIndex + s_window;
			if (m_addedThrough >= target || m_addedThrough + 1 >= m_tracks.Count)
			{
				return;
			}

			m_fetching = true;
			int generation = m_generation;
			PulseTrack next = m_tracks[m_addedThrough + 1];
			m_data.GetTrackAudioFile(next, (localPath) =>
			{
				MainThread.BeginInvokeOnMainThread(() =>
				{
					if (generation != m_generation)
					{
						m_fetching = false;
						return;
					}
					if (!string.IsNullOrEmpty(localPath))
					{
						m_player.AddMediaItem(BuildItem(next, localPath));
					}
					m_addedThrough = m_addedThrough + 1;
					m_fetching = false;
				});
			});
		}

		private static MediaItem BuildItem(PulseTrack track, string localPath)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(track.Title);
			metadata.SetArtist(track.Artist);
			if (!string.IsNullOrEmpty(track.Album))
			{
				metadata.SetAlbumTitle(track.Album);
			}

			Android.Net.Uri uri = Android.Net.Uri.FromFile(new Java.IO.File(localPath));
			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(track.Id);
			builder.SetUri(uri);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
		}
	}
}

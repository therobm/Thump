using System.Collections.Generic;
using System.Timers;
using Android.Content;
using Android.Runtime;
using AndroidX.Media3.Common;
using AndroidX.Media3.Session;
using Microsoft.Maui.ApplicationModel;
using Thump.Data;
using Thump.Pulse;

namespace Thump.Playback
{
	public class AndroidThumpPlayer : IThumpPlayer
	{
		private const int s_stateIdle = 1;
		private const int s_stateBuffering = 2;
		private const int s_stateReady = 3;
		private const int s_stateEnded = 4;
		private const int s_repeatOff = 0;
		private const int s_repeatOne = 1;
		private const int s_repeatAll = 2;
		private const double s_tickIntervalMs = 500;

		private MainView m_mainView;
		private MediaController m_controller;
		private Google.Common.Util.Concurrent.IListenableFuture m_controllerFuture;
		private Timer m_ticker;

		private List<PulseTrack> m_queue = new List<PulseTrack>();
		private int m_startIndex;
		private int m_generation;
		private bool m_pendingPlay;
		private ePlaybackState m_lastState = ePlaybackState.Idle;
		private string m_lastMediaId;
		private bool m_endHandled;
		private bool m_shuffleEnabled;
		private eRepeatMode m_repeatMode = eRepeatMode.Off;
		private ThumpData m_data;

		public AndroidThumpPlayer(MainView mainView, ThumpData thumpData)
		{
			m_mainView = mainView;
			m_data = thumpData;

			ThumpPlaybackService.s_ThumpData = m_data;

			Context context = Android.App.Application.Context;
			ComponentName componentName = new ComponentName(context, Java.Lang.Class.FromType(typeof(ThumpPlaybackService)));
			SessionToken token = new SessionToken(context, componentName);
			MediaController.Builder controllerBuilder = new MediaController.Builder(context, token);
			m_controllerFuture = controllerBuilder.BuildAsync();
			m_controllerFuture.AddListener(new Java.Lang.Runnable(OnControllerConnected), AndroidX.Core.Content.ContextCompat.GetMainExecutor(context));

			m_ticker = new Timer(s_tickIntervalMs);
			m_ticker.AutoReset = true;
			m_ticker.Elapsed += OnTickerElapsed;
		}

		private void OnControllerConnected()
		{
			try
			{
				Java.Lang.Object result = m_controllerFuture.Get();
				m_controller = result.JavaCast<MediaController>();
			}
			catch (System.Exception ex)
			{
				Log.Exception(ex);
				return;
			}
			ApplyShuffleMode();
			ApplyRepeatMode();
			if (m_pendingPlay)
			{
				m_pendingPlay = false;
				StartQueue();
			}
		}

		public void Play(List<PulseTrack> tracks, int startIndex)
		{
			if (tracks == null || tracks.Count == 0)
			{
				return;
			}
			m_queue = tracks;
			m_startIndex = startIndex;
			m_generation = m_generation + 1;
			m_endHandled = false;
			m_lastMediaId = null;

			if (m_controller == null)
			{
				m_pendingPlay = true;
				return;
			}
			StartQueue();
		}

		private void StartQueue()
		{
			if (m_startIndex < 0 || m_startIndex >= m_queue.Count)
			{
				return;
			}
			int generation = m_generation;
			m_controller.ClearMediaItems();

			PulseTrack startTrack = m_queue[m_startIndex];
			m_data.GetTrackAudioFile(startTrack, (localPath) =>
			{
				if (generation != m_generation)
				{
					return;
				}
				if (string.IsNullOrEmpty(localPath))
				{
					Log.Error("AndroidThumpPlayer: failed to obtain audio file for start track.");
					ReportState(ePlaybackState.Idle);
					return;
				}
				MediaItem item = BuildMediaItem(startTrack, localPath);
				m_controller.SetMediaItem(item);
				m_controller.Prepare();
				m_controller.Play();
				m_ticker.Start();

				m_lastMediaId = startTrack.Id;
				m_mainView.OnCurrentTrackChanged(startTrack);

				FillForward(m_startIndex + 1, generation);
				FillBackward(m_startIndex - 1, generation);
			});
		}

		private void FillForward(int index, int generation)
		{
			if (generation != m_generation || index >= m_queue.Count)
			{
				return;
			}
			PulseTrack track = m_queue[index];
			m_data.GetTrackAudioFile(track, (localPath) =>
			{
				if (generation != m_generation)
				{
					return;
				}
				if (!string.IsNullOrEmpty(localPath))
				{
					m_controller.AddMediaItem(BuildMediaItem(track, localPath));
				}
				FillForward(index + 1, generation);
			});
		}

		private void FillBackward(int index, int generation)
		{
			if (generation != m_generation || index < 0)
			{
				return;
			}
			PulseTrack track = m_queue[index];
			m_data.GetTrackAudioFile(track, (localPath) =>
			{
				if (generation != m_generation)
				{
					return;
				}
				if (!string.IsNullOrEmpty(localPath))
				{
					m_controller.AddMediaItem(0, BuildMediaItem(track, localPath));
				}
				FillBackward(index - 1, generation);
			});
		}

		private static MediaItem BuildMediaItem(PulseTrack track, string localPath)
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

		public void Pause()
		{
			if (m_controller == null)
			{
				return;
			}
			m_controller.Pause();
		}

		public void Resume()
		{
			if (m_controller == null)
			{
				return;
			}
			m_controller.Play();
		}

		public void SeekTo(long positionMilliseconds)
		{
			if (m_controller == null)
			{
				return;
			}
			m_controller.SeekTo(positionMilliseconds);
		}

		public void Next()
		{
			if (m_controller == null)
			{
				return;
			}
			m_controller.SeekToNextMediaItem();
		}

		public void Previous()
		{
			if (m_controller == null)
			{
				return;
			}
			m_controller.SeekToPreviousMediaItem();
		}

		public void SetShuffleEnabled(bool enabled)
		{
			m_shuffleEnabled = enabled;
			if (m_controller == null)
			{
				return;
			}
			ApplyShuffleMode();
		}

		private void ApplyShuffleMode()
		{
			m_controller.ShuffleModeEnabled = m_shuffleEnabled;
		}

		public void SetRepeatMode(eRepeatMode mode)
		{
			m_repeatMode = mode;
			if (m_controller == null)
			{
				return;
			}
			ApplyRepeatMode();
		}

		private void ApplyRepeatMode()
		{
			int mapped;
			if (m_repeatMode == eRepeatMode.One)
			{
				mapped = s_repeatOne;
			}
			else if (m_repeatMode == eRepeatMode.All)
			{
				mapped = s_repeatAll;
			}
			else
			{
				mapped = s_repeatOff;
			}
			m_controller.RepeatMode = mapped;
		}

		public void AddToQueue(List<PulseTrack> tracks)
		{
			if (m_controller == null || tracks == null || tracks.Count == 0)
			{
				return;
			}
			int generation = m_generation;
			AppendQueueItem(tracks, 0, generation);
			m_queue.AddRange(tracks);
		}

		private void AppendQueueItem(List<PulseTrack> tracks, int index, int generation)
		{
			if (generation != m_generation || index >= tracks.Count)
			{
				return;
			}
			PulseTrack track = tracks[index];
			m_data.GetTrackAudioFile(track, (localPath) =>
			{
				if (generation != m_generation)
				{
					return;
				}
				if (!string.IsNullOrEmpty(localPath))
				{
					m_controller.AddMediaItem(BuildMediaItem(track, localPath));
				}
				AppendQueueItem(tracks, index + 1, generation);
			});
		}

		public void PlayNext(List<PulseTrack> tracks)
		{
			if (m_controller == null || tracks == null || tracks.Count == 0)
			{
				return;
			}
			int generation = m_generation;
			int insertAt = m_controller.CurrentMediaItemIndex + 1;
			int count = m_controller.MediaItemCount;
			if (insertAt > count)
			{
				insertAt = count;
			}
			InsertQueueItem(tracks, 0, insertAt, generation);
			m_queue.InsertRange(insertAt, tracks);
		}

		private void InsertQueueItem(List<PulseTrack> tracks, int index, int insertAt, int generation)
		{
			if (generation != m_generation || index >= tracks.Count)
			{
				return;
			}
			PulseTrack track = tracks[index];
			m_data.GetTrackAudioFile(track, (localPath) =>
			{
				if (generation != m_generation)
				{
					return;
				}
				if (!string.IsNullOrEmpty(localPath))
				{
					m_controller.AddMediaItem(insertAt, BuildMediaItem(track, localPath));
				}
				InsertQueueItem(tracks, index + 1, insertAt + 1, generation);
			});
		}

		public void SeekToQueueItem(int index)
		{
			if (m_controller == null || index < 0)
			{
				return;
			}
			m_endHandled = false;
			m_controller.SeekTo(index, 0L);
			m_controller.Play();
			m_ticker.Start();
		}

		public void Stop()
		{
			m_ticker.Stop();
			if (m_controller == null)
			{
				return;
			}
			m_controller.Stop();
			ReportState(ePlaybackState.Idle);
		}

		public void Release()
		{
			m_ticker.Stop();
			if (m_controller != null)
			{
				m_controller.Release();
				m_controller = null;
			}
		}

		private void OnTickerElapsed(object sender, ElapsedEventArgs e)
		{
			MainThread.BeginInvokeOnMainThread(() =>
			{
				Tick();
			});
		}

		private void Tick()
		{
			if (m_controller == null)
			{
				return;
			}

			int playbackState = m_controller.PlaybackState;
			bool isPlaying = m_controller.IsPlaying;

			ePlaybackState mapped;
			if (playbackState == s_stateEnded)
			{
				mapped = ePlaybackState.Ended;
			}
			else if (playbackState == s_stateBuffering)
			{
				mapped = ePlaybackState.Buffering;
			}
			else if (playbackState == s_stateReady)
			{
				if (isPlaying)
				{
					mapped = ePlaybackState.Playing;
				}
				else
				{
					mapped = ePlaybackState.Paused;
				}
			}
			else
			{
				mapped = ePlaybackState.Idle;
			}
			ReportState(mapped);

			long position = m_controller.CurrentPosition;
			long duration = m_controller.Duration;
			if (position < 0)
			{
				position = 0;
			}
			if (duration < 0)
			{
				duration = 0;
			}
			m_mainView.OnPlaybackPositionChanged(position, duration);

			DetectTrackChange();

			if (playbackState == s_stateEnded)
			{
				HandleQueueEnded();
			}
		}

		private void DetectTrackChange()
		{
			MediaItem current = m_controller.CurrentMediaItem;
			if (current == null)
			{
				return;
			}
			string mediaId = current.MediaId;
			if (string.IsNullOrEmpty(mediaId))
			{
				return;
			}
			if (mediaId == m_lastMediaId)
			{
				return;
			}
			m_lastMediaId = mediaId;
			PulseTrack track = FindTrackById(mediaId);
			if (track != null)
			{
				m_mainView.OnCurrentTrackChanged(track);
			}
		}

		private PulseTrack FindTrackById(string trackId)
		{
			for (int idx = 0; idx < m_queue.Count; idx++)
			{
				if (m_queue[idx].Id == trackId)
				{
					return m_queue[idx];
				}
			}
			return null;
		}

		private void HandleQueueEnded()
		{
			if (m_endHandled)
			{
				return;
			}
			m_endHandled = true;
			m_ticker.Stop();
			m_mainView.OnTrackEnded();
		}

		private void ReportState(ePlaybackState state)
		{
			if (state == m_lastState)
			{
				return;
			}
			m_lastState = state;
			m_mainView.OnPlaybackStateChanged(state);
		}
	}
}

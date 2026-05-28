using System.Collections.Generic;
using Thump.Pulse;

namespace Thump.Playback
{
	public class StubThumpPlayer : IThumpPlayer
	{
		public void Play(List<PulseTrack> tracks, int startIndex)
		{
			Log.Warn("StubThumpPlayer.Play: playback is only implemented on Android.");
		}

		public void Pause()
		{
		}

		public void Resume()
		{
		}

		public void SeekTo(long positionMilliseconds)
		{
		}

		public void Next()
		{
		}

		public void Previous()
		{
		}

		public void Stop()
		{
		}

		public void Release()
		{
		}
	}
}

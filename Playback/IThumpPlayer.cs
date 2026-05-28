using System.Collections.Generic;
using Thump.Pulse;

namespace Thump.Playback
{
	public enum ePlaybackState
	{
		Idle,
		Buffering,
		Playing,
		Paused,
		Ended,
	}

	public interface IThumpPlayer
	{
		void Play(List<PulseTrack> tracks, int startIndex);
		void Pause();
		void Resume();
		void SeekTo(long positionMilliseconds);
		void Next();
		void Previous();
		void Stop();
		void Release();
	}
}

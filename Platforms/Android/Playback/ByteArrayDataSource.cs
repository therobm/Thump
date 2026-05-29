using System.Threading;
using AndroidX.Media3.Common;
using AndroidX.Media3.DataSource;
using Thump.Data;
using Thump.Pulse;

namespace Thump.Playback
{
	public class ByteArrayDataSource : BaseDataSource
	{
		private ThumpData m_data;
		private byte[] m_bytes;
		private Android.Net.Uri m_uri;
		private int m_readPosition;
		private int m_bytesRemaining;

		public ByteArrayDataSource(ThumpData data) : base(false)
		{
			m_data = data;
		}

		public override long Open(DataSpec dataSpec)
		{
			TransferInitializing(dataSpec);
			m_uri = dataSpec.Uri;
			string trackId = m_uri.Host;
			if (string.IsNullOrEmpty(trackId))
			{
				trackId = m_uri.LastPathSegment;
			}

			PulseTrack track = new PulseTrack();
			track.Id = trackId;

			ManualResetEventSlim wait = new ManualResetEventSlim(false);
		
			m_bytes = m_data.GetTrackAudioData(track);
			if (m_bytes == null)
			{
				throw new Java.IO.IOException("No audio data for " + trackId);
			}

			m_readPosition = (int)dataSpec.Position;
			m_bytesRemaining = m_bytes.Length - m_readPosition;
			if (dataSpec.Length != C.LengthUnset)
			{
				m_bytesRemaining = (int)System.Math.Min(m_bytesRemaining, dataSpec.Length);
			}

			TransferStarted(dataSpec);
			return m_bytesRemaining;
		}

		public override int Read(byte[] buffer, int offset, int length)
		{
			if (length == 0)
			{
				return 0;
			}
			if (m_bytesRemaining == 0)
			{
				return C.ResultEndOfInput;
			}
			int toRead = System.Math.Min(length, m_bytesRemaining);
			System.Array.Copy(m_bytes, m_readPosition, buffer, offset, toRead);
			m_readPosition += toRead;
			m_bytesRemaining -= toRead;
			BytesTransferred(toRead);
			return toRead;
		}

		public override Android.Net.Uri Uri
		{
			get { return m_uri; }
		}

		public override void Close()
		{
			if (m_uri != null)
			{
				TransferEnded();
			}
			m_uri = null;
			m_bytes = null;
		}
	}
}
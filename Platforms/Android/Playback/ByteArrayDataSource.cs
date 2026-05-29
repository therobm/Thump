using System.Threading;
using AndroidX.Media3.Common;
using AndroidX.Media3.DataSource;
using Thump.Data;
using Thump.Pulse;

namespace Thump.Playback
{
	public class ByteArrayDataSource : BaseDataSource
	{

		private static int GetAudioStartOffset(byte[] bytes)
		{
			// Skip a leading ID3v2 tag so the stream starts at the first audio frame.
			if (bytes.Length < 10)
			{
				return 0;
			}
			if (bytes[0] != 0x49 || bytes[1] != 0x44 || bytes[2] != 0x33)   // "ID3"
			{
				return 0;
			}
			int flags = bytes[5];
			int size = ((bytes[6] & 0x7F) << 21)
					 | ((bytes[7] & 0x7F) << 14)
					 | ((bytes[8] & 0x7F) << 7)
					 | (bytes[9] & 0x7F);          // synchsafe int
			int offset = 10 + size;
			if ((flags & 0x10) != 0)                 // footer present
			{
				offset = offset + 10;
			}
			if (offset < 10 || offset > bytes.Length)
			{
				return 0;
			}
			return offset;
		}

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

			m_bytes = m_data.GetTrackAudioData(track);
			if (m_bytes == null)
			{
				throw new Java.IO.IOException("No audio data for " + trackId);
			}

			int audioStart = GetAudioStartOffset(m_bytes);

			m_readPosition = audioStart + (int)dataSpec.Position;
			m_bytesRemaining = (m_bytes.Length - audioStart) - (int)dataSpec.Position;
			if (dataSpec.Length != C.LengthUnset)
			{
				m_bytesRemaining = (int)System.Math.Min(m_bytesRemaining, dataSpec.Length);
			}
			if (m_bytesRemaining < 0)
			{
				m_bytesRemaining = 0;
			}

			TransferStarted(dataSpec);
			//Log.Warn("ByteArrayDataSource.Open done bytes=" + m_bytes.Length + " remaining=" + m_bytesRemaining);
			return m_bytesRemaining;
		}


		public override int Read(byte[] buffer, int offset, int length)
		{
			if (length == 0)
			{
				return 0;
			}
			if (m_bytesRemaining <= 0)
			{
				Log.Warn("ByteArrayDataSource.Read EOF at pos=" + m_readPosition);
				return C.ResultEndOfInput;
			}
			int toRead = System.Math.Min(length, m_bytesRemaining);
			System.Array.Copy(m_bytes, m_readPosition, buffer, offset, toRead);
			m_readPosition += toRead;
			m_bytesRemaining -= toRead;
			//Log.Warn("ByteArrayDataSource.Read len=" + length + " toRead=" + toRead + " pos=" + m_readPosition + " of=" + m_bytes.Length);
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
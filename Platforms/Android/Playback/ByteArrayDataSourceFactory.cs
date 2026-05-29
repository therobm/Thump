using AndroidX.Media3.DataSource;
using Thump.Data;

namespace Thump.Playback
{
	public class ByteArrayDataSourceFactory : Java.Lang.Object, IDataSourceFactory
	{
		private ThumpData m_data;

		public ByteArrayDataSourceFactory(ThumpData data)
		{
			m_data = data;
		}

		public IDataSource CreateDataSource()
		{
			return new ByteArrayDataSource(m_data);
		}
	}
}
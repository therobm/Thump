using AndroidX.Media3.Common;
using Bumptech.Glide.Load.Model;
using Thump.Pulse;

namespace Thump.Playback
{
	public static  class MediaItemBuilder
	{
		public static MediaItem Build(PulseTrack track)
		{
			MediaMetadata.Builder metadata = new MediaMetadata.Builder();
			metadata.SetTitle(track.Title);
			metadata.SetArtist(track.Artist);
			if (!string.IsNullOrEmpty(track.Album))
			{
				metadata.SetAlbumTitle(track.Album);
			}

			Android.Net.Uri uri = GetURI(track);
			MediaItem.Builder builder = new MediaItem.Builder();
			builder.SetMediaId(track.Id);
			builder.SetUri(uri);
			builder.SetMediaMetadata(metadata.Build());
			return builder.Build();
		}
		public static Android.Net.Uri GetURI(PulseTrack track)
		{
			Android.Net.Uri uri = Android.Net.Uri.Parse("thump://" + track.Id);
			return uri;
		}
	}
}

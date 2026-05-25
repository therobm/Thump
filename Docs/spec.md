# Thump

OpenSubsonic music player for Android. Works with any OpenSubsonic server. Optional support for Pulse-specific endpoints when connected to a Pulse server.

## Server first via passthrough cache

The client always sources its local cache for any data it needs. The server (if available) should always be called to bust local cache when available. The goal is to have an always up to date experience when online, while still having a fallback path for times the server isn't available.

This applies to everything: metadata, playlists, home screen content, cover art. No separate online/offline mode. No sync. No merge. Server response replaces local data, always.

## Audio cache

Audio files are written to disk as they stream, keyed by track ID. Same track ID always means the same audio, so cached files never go stale.

When the cache exceeds the size cap (user configurable, default 500 MB), the least recently played files get deleted first to make room.

When a playlist or album is playing, the client should fetch the next N tracks ahead in the background (configurable, default 10) so they're ready when needed. This means gapless playback actually works on spotty connections, and going into a tunnel mid-playlist isn't an immediate problem.

No pin/download feature — the cache fills from normal playback and lookahead.

## API

### OpenSubsonic (required)

Standard auth and standard endpoint behavior. The full list of endpoints used:

**System:** ping, getLicense, getUser, getOpenSubsonicExtensions, getMusicFolders

**Browsing:** getArtists, getArtist, getAlbum, getAlbumList2, getGenres, getIndexes, getMusicDirectory, getSong, search3

**Playlists:** getPlaylists, getPlaylist, createPlaylist, updatePlaylist, deletePlaylist

**Favorites:** getStarred2, star, unstar, setRating

**Playback:** stream, getCoverArt, scrobble

### Pulse extensions (optional)

Detected automatically on first connect. If the server supports them, the home screen uses them. If not, it falls back to standard OpenSubsonic calls.

- `pulse/recentlyPlayed` — recent tracks, newest first
- `pulse/popularArtists` — artists ranked by listening score
- `pulse/topPlaylists` — playlists ranked by relevance

## UI

### Navigation

Bottom bar: Home, Library, Search, Settings.

### Home screen

Scrollable list of horizontal carousels.

**With Pulse:**
1. Recently Played — `pulse/recentlyPlayed`
2. Your Playlists — `pulse/topPlaylists`
3. Popular Artists — `pulse/popularArtists`
4. Recently Added — `getAlbumList2?type=newest`
5. Favorites — `getStarred2`

**Without Pulse:**
1. Recently Played — `getAlbumList2?type=recent`
2. Playlists — `getPlaylists`
3. Most Played — `getAlbumList2?type=frequent`
4. Recently Added — `getAlbumList2?type=newest`
5. Favorites — `getStarred2`

Tap an item to open it. No filters, no sort controls.

### Album / Playlist detail

Large cover art, track list, play and shuffle buttons. Per-track menu: play next, add to queue, star/unstar.

### Now playing

Full screen from tapping the mini player. Cover art, track info, seek bar, play/pause, skip, shuffle, repeat, favorite, queue button (reorderable list).

### Mini player

Bottom bar when something is playing. Art, title, artist, play/pause. Tap to expand.

### Android Auto

Browse tree: Home, Playlists, Artists, Albums, Recently Played. Android Auto renders its own UI from this.

## Playback

- Gapless playback
- Scrobble to the server on play and completion
- Volume normalization if the audio files have the tags for it (ReplayGain)
- Queue survives app restarts
- Resume last track, position, and queue on restart

## Settings

- Server URL
- Username and password
- Prefetch limit — how many upcoming tracks to pre-download during playback (default 10)
- Audio cache size (default 500 MB, 100 MB – 5 GB)
- Clear cache
- Scrobble on/off
- Normalize volume — off / per track / per album

## Not included

- Local file playback
- Playlist editing on the client
- Tag editing
- EQ or audio processing
- Multiple servers
- Podcasts
- Casting (maybe later)
- Cloud storage
- Explicit offline/download management

## Tech stack

- Kotlin, Jetpack Compose
- Media3 (ExoPlayer)
- Android Auto via MediaLibraryService
- Min SDK 26 (Android 8.0), target latest stable
- MIT license, public repo
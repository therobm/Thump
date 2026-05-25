package com.therobm.thump.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the single ExoPlayer instance and exposes a small surface for the UI.
 *
 * Holds a single queue at a time. Calling playQueue replaces whatever was loaded and starts
 * playback at the given index; ExoPlayer auto-advances through the rest. The nowPlaying flow
 * tracks the currently-loaded item and whether it's playing or paused, updated both by direct
 * calls and by the player's own transition events (so auto-advance keeps the mini player in
 * sync).
 *
 * Construct one per app session, hold it across recompositions, and call release() exactly
 * once when the host activity is destroyed.
 */
class PlaybackController(applicationContext: Context) {

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(applicationContext).build()

    private val nowPlayingFlow: MutableStateFlow<NowPlaying?> = MutableStateFlow(null)
    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingFlow

    private var currentQueueMetadata: List<PlaybackQueueItem> = emptyList()

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateIsPlayingFlag(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshNowPlayingFromCurrentIndex()
            }
        })
    }

    /**
     * Replace the current queue with the given items and start playback at startIndex.
     *
     * Items at indexes after startIndex are queued behind it; ExoPlayer auto-advances through
     * the queue when each track finishes.
     */
    fun playQueue(items: List<PlaybackQueueItem>, startIndex: Int) {
        if (items.isEmpty()) {
            return
        }
        val safeStartIndex: Int
        if (startIndex < 0) {
            safeStartIndex = 0
        } else if (startIndex >= items.size) {
            safeStartIndex = items.size - 1
        } else {
            safeStartIndex = startIndex
        }

        currentQueueMetadata = items

        val mediaItems = ArrayList<MediaItem>(items.size)
        val itemCount = items.size
        for (itemIndex in 0 until itemCount) {
            mediaItems.add(MediaItem.fromUri(items[itemIndex].streamUrl))
        }

        exoPlayer.setMediaItems(mediaItems, safeStartIndex, 0L)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        publishNowPlayingFor(safeStartIndex, isPlayingHint = true)
    }

    fun pause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }

    fun resume() {
        if (!exoPlayer.isPlaying) {
            exoPlayer.play()
        }
    }

    /**
     * Move to the next item in the queue. No-op if there is no next item.
     */
    fun skipToNext() {
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNext()
        }
    }

    /**
     * Move to the previous item in the queue. Convention follows most media apps: if the user
     * is more than a few seconds into the current track, the press restarts the current track
     * instead of going back.
     */
    fun skipToPrevious() {
        if (exoPlayer.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
            exoPlayer.seekTo(0L)
            return
        }
        if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPrevious()
            return
        }
        exoPlayer.seekTo(0L)
    }

    /**
     * Seek to an absolute position inside the current track. Clamps to [0, duration].
     */
    fun seekTo(positionMs: Long) {
        val duration = exoPlayer.duration
        val clamped: Long
        if (positionMs < 0L) {
            clamped = 0L
        } else if (duration > 0L && positionMs > duration) {
            clamped = duration
        } else {
            clamped = positionMs
        }
        exoPlayer.seekTo(clamped)
    }

    /**
     * Current playback position inside the active track, in milliseconds. Polled from the UI
     * for the seek bar — ExoPlayer does not expose a flow for this.
     */
    fun currentPositionMs(): Long {
        return exoPlayer.currentPosition
    }

    /**
     * Total duration of the active track, in milliseconds. Returns 0 when unknown (e.g. before
     * the player has loaded the track).
     */
    fun durationMs(): Long {
        val duration = exoPlayer.duration
        if (duration <= 0L) {
            return 0L
        }
        return duration
    }

    fun hasNext(): Boolean {
        return exoPlayer.hasNextMediaItem()
    }

    fun hasPrevious(): Boolean {
        return exoPlayer.hasPreviousMediaItem()
    }

    fun release() {
        exoPlayer.release()
    }

    private fun updateIsPlayingFlag(isPlaying: Boolean) {
        val current = nowPlayingFlow.value
        if (current == null) {
            return
        }
        if (current.isPlaying == isPlaying) {
            return
        }
        nowPlayingFlow.value = current.copy(isPlaying = isPlaying)
    }

    private fun refreshNowPlayingFromCurrentIndex() {
        val newIndex = exoPlayer.currentMediaItemIndex
        publishNowPlayingFor(newIndex, isPlayingHint = exoPlayer.isPlaying)
    }

    private fun publishNowPlayingFor(index: Int, isPlayingHint: Boolean) {
        if (index < 0 || index >= currentQueueMetadata.size) {
            return
        }
        val item = currentQueueMetadata[index]
        nowPlayingFlow.value = NowPlaying(
            trackId = item.trackId,
            title = item.title,
            artist = item.artist,
            album = item.album,
            coverArtUrl = item.coverArtUrl,
            isPlaying = isPlayingHint,
        )
    }

    companion object {
        // Pressing previous within this many ms of the start of a track moves to the previous
        // track. After this, previous restarts the current track. Standard media-app convention.
        private const val PREVIOUS_RESTART_THRESHOLD_MS: Long = 3000L
    }
}

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
 * This proof-of-concept supports playing one track at a time. The next iteration will introduce
 * a queue, MediaSessionService for lock-screen / Android Auto integration, and resume-on-launch
 * state restoration.
 *
 * Construct one per app session, hold it across recompositions, and call release() exactly
 * once when the host activity is destroyed.
 */
class PlaybackController(applicationContext: Context) {

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(applicationContext).build()

    private val nowPlayingFlow: MutableStateFlow<NowPlaying?> = MutableStateFlow(null)
    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingFlow

    init {
        // Listen for play/pause and load transitions so the mini player reflects what the
        // engine actually thinks is happening, not what we hoped would happen when we kicked
        // off the request.
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateIsPlayingFlag(isPlaying)
            }
        })
    }

    /**
     * Stop whatever's playing and start the given track from the beginning.
     */
    fun play(
        trackId: String,
        streamUrl: String,
        title: String,
        artist: String,
        coverArtUrl: String?,
    ) {
        val mediaItem: MediaItem = MediaItem.fromUri(streamUrl)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        nowPlayingFlow.value = NowPlaying(
            trackId = trackId,
            title = title,
            artist = artist,
            coverArtUrl = coverArtUrl,
            isPlaying = true,
        )
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
}

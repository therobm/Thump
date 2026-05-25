package com.therobm.thump.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the app-side MediaController that drives the playback service.
 *
 * Compose holds one instance per session, calls `connect()` once it is composed, and `release()`
 * on dispose. Until the controller resolves, playback calls are no-ops — the UI only triggers
 * them in response to user input that happens after the activity is alive, by which point the
 * async connect has almost always completed.
 */
class PlaybackController(applicationContext: Context) {

    private val resolvedApplicationContext: Context = applicationContext.applicationContext

    private val nowPlayingFlow: MutableStateFlow<NowPlaying?> = MutableStateFlow(null)
    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingFlow

    private var mediaController: MediaController? = null
    private var pendingControllerFuture: ListenableFuture<MediaController>? = null

    private var currentQueueMetadata: List<PlaybackQueueItem> = emptyList()
    private var currentQueueSource: PlaybackSource? = null

    private val playerListener: Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateIsPlayingFlag(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshNowPlayingFromCurrentIndex()
        }
    }

    /**
     * Start the async connection to ThumpPlaybackService. Safe to call repeatedly; subsequent
     * calls while a connect is in flight or a controller is already bound are no-ops.
     */
    fun connect() {
        if (mediaController != null) {
            return
        }
        if (pendingControllerFuture != null) {
            return
        }
        val sessionToken = SessionToken(
            resolvedApplicationContext,
            ComponentName(resolvedApplicationContext, ThumpPlaybackService::class.java),
        )
        val future = MediaController.Builder(resolvedApplicationContext, sessionToken).buildAsync()
        pendingControllerFuture = future
        future.addListener(
            {
                try {
                    val controller = future.get()
                    controller.addListener(playerListener)
                    mediaController = controller
                } catch (controllerBuildFailure: Exception) {
                    // No usable controller — keep mediaController null so UI calls become no-ops.
                }
                pendingControllerFuture = null
            },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * Replace the current queue with the given items and start playback at startIndex. No-op if
     * the controller has not connected yet.
     */
    fun playQueue(items: List<PlaybackQueueItem>, startIndex: Int, source: PlaybackSource?) {
        val controller = mediaController
        if (controller == null) {
            return
        }
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
        currentQueueSource = source

        val mediaItems = ArrayList<MediaItem>(items.size)
        val itemCount = items.size
        for (itemIndex in 0 until itemCount) {
            mediaItems.add(MediaItem.fromUri(items[itemIndex].streamUrl))
        }

        controller.setMediaItems(mediaItems, safeStartIndex, 0L)
        controller.prepare()
        controller.playWhenReady = true

        publishNowPlayingFor(safeStartIndex, isPlayingHint = true)
    }

    fun pause() {
        val controller = mediaController
        if (controller == null) {
            return
        }
        if (controller.isPlaying) {
            controller.pause()
        }
    }

    fun resume() {
        val controller = mediaController
        if (controller == null) {
            return
        }
        if (!controller.isPlaying) {
            controller.play()
        }
    }

    fun skipToNext() {
        val controller = mediaController
        if (controller == null) {
            return
        }
        if (controller.hasNextMediaItem()) {
            controller.seekToNext()
        }
    }

    fun skipToPrevious() {
        val controller = mediaController
        if (controller == null) {
            return
        }
        if (controller.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
            controller.seekTo(0L)
            return
        }
        if (controller.hasPreviousMediaItem()) {
            controller.seekToPrevious()
            return
        }
        controller.seekTo(0L)
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController
        if (controller == null) {
            return
        }
        val duration = controller.duration
        val clamped: Long
        if (positionMs < 0L) {
            clamped = 0L
        } else if (duration > 0L && positionMs > duration) {
            clamped = duration
        } else {
            clamped = positionMs
        }
        controller.seekTo(clamped)
    }

    fun currentPositionMs(): Long {
        val controller = mediaController
        if (controller == null) {
            return 0L
        }
        return controller.currentPosition
    }

    fun durationMs(): Long {
        val controller = mediaController
        if (controller == null) {
            return 0L
        }
        val duration = controller.duration
        if (duration <= 0L) {
            return 0L
        }
        return duration
    }

    fun hasNext(): Boolean {
        val controller = mediaController
        if (controller == null) {
            return false
        }
        return controller.hasNextMediaItem()
    }

    fun hasPrevious(): Boolean {
        val controller = mediaController
        if (controller == null) {
            return false
        }
        return controller.hasPreviousMediaItem()
    }

    fun release() {
        val controller = mediaController
        if (controller != null) {
            controller.removeListener(playerListener)
            controller.release()
            mediaController = null
        }
        val pending = pendingControllerFuture
        if (pending != null) {
            pending.cancel(false)
            pendingControllerFuture = null
        }
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
        val controller = mediaController
        if (controller == null) {
            return
        }
        val newIndex = controller.currentMediaItemIndex
        publishNowPlayingFor(newIndex, isPlayingHint = controller.isPlaying)
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
            source = currentQueueSource,
        )
    }

    companion object {
        // Pressing previous within this many ms of the start of a track moves to the previous
        // track. After this, previous restarts the current track. Standard media-app convention.
        private const val PREVIOUS_RESTART_THRESHOLD_MS: Long = 3000L
    }
}

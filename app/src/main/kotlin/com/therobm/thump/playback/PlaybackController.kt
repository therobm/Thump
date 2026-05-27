package com.therobm.thump.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.therobm.thump.data.ThumpData
import com.therobm.thump.data.ThumpDataNotConfigured
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Owns the app-side MediaController that drives the playback service.
 *
 * Compose holds one instance per session, calls `connect()` once it is composed, and `release()`
 * on dispose. Until the controller resolves, playback calls are no-ops — the UI only triggers
 * them in response to user input that happens after the activity is alive, by which point the
 * async connect has almost always completed.
 */
class PlaybackController(applicationContext: Context, thumpData: ThumpData) {

    private val resolvedApplicationContext: Context = applicationContext.applicationContext
    private val persistence: PlaybackPersistence = PlaybackPersistence(resolvedApplicationContext)
    private val thumpDataForPrefetch: ThumpData = thumpData

    private val nowPlayingFlow: MutableStateFlow<NowPlaying?> = MutableStateFlow(null)
    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingFlow

    private var mediaController: MediaController? = null
    private var pendingControllerFuture: ListenableFuture<MediaController>? = null

    private var currentQueueMetadata: List<PlaybackQueueItem> = emptyList()
    private var currentQueueSource: PlaybackSource? = null

    // Dedicated scope for the prefetch-then-load handoff in playQueue. Lives on Main.immediate
    // because the MediaController calls inside the coroutine (setMediaItems / prepare /
    // playWhenReady) all require the MediaController's application looper, which is Main, and
    // we want the synchronous portion before the first suspension to stay on the caller's
    // (Compose lambda) thread without an extra dispatch hop. SupervisorJob so one failed
    // playQueue does not poison subsequent launches. Cancelled in release() so an in-flight
    // prefetch never resolves into a released controller.
    private val playbackCoroutineScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate,
    )

    private val playerListener: Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateIsPlayingFlag(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshNowPlayingFromCurrentIndex()
        }
    }

    // Custom-command channel the playback service uses to push the unavailable-track reason
    // string across the process boundary. NowPlaying lives in this (UI) process inside
    // nowPlayingFlow, and the service cannot mutate it directly. The service's gate and safety
    // net both broadcast a SessionCommand carrying the failing trackId and a human-readable
    // reason; this listener parses that and routes it through publishNowPlayingFor so the mini
    // player and Now Playing screen render the unavailable banner. The toast itself fires on
    // the service side (single source of truth for ExoPlayer state), so this side only updates
    // the flow.
    private val controllerListener: MediaController.Listener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == SESSION_COMMAND_UNAVAILABLE_REASON) {
                handleUnavailableReasonCommand(args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
        val future: ListenableFuture<MediaController> = MediaController.Builder(
            resolvedApplicationContext,
            sessionToken,
        )
            .setListener(controllerListener)
            .buildAsync()
        pendingControllerFuture = future
        future.addListener(
            {
                try {
                    val controller = future.get()
                    controller.addListener(playerListener)
                    mediaController = controller
                    // The service may have restored a saved queue into the player on its onCreate
                    // (resume-on-launch). Mirror that state into our local metadata cache and
                    // surface the current track to the UI so the mini player shows the resumed
                    // track immediately without waiting for the user to start playback.
                    hydrateFromPersistedState()
                } catch (controllerBuildFailure: Exception) {
                    // No usable controller — keep mediaController null so UI calls become no-ops.
                }
                pendingControllerFuture = null
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun hydrateFromPersistedState() {
        val persisted = persistence.load()
        if (persisted == null) {
            return
        }
        val rehydratedItems = ArrayList<PlaybackQueueItem>(persisted.items.size)
        val itemCount = persisted.items.size
        for (itemIndex in 0 until itemCount) {
            val item = persisted.items[itemIndex]
            rehydratedItems.add(
                PlaybackQueueItem(
                    trackId = item.trackId,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    coverArtId = item.coverArtId,
                )
            )
        }
        currentQueueMetadata = rehydratedItems
        val source = persisted.source
        if (source == null) {
            currentQueueSource = null
        } else {
            val kind: PlaybackSourceKind
            when (source.kind) {
                PlaybackSourceKind.Album.name -> kind = PlaybackSourceKind.Album
                PlaybackSourceKind.Playlist.name -> kind = PlaybackSourceKind.Playlist
                PlaybackSourceKind.Artist.name -> kind = PlaybackSourceKind.Artist
                PlaybackSourceKind.Genre.name -> kind = PlaybackSourceKind.Genre
                else -> kind = PlaybackSourceKind.Album
            }
            currentQueueSource = PlaybackSource(kind = kind, name = source.name)
        }
        publishNowPlayingFor(persisted.currentIndex, isPlayingHint = false, unavailableReason = null)
    }

    /**
     * Replace the current queue with the given items and start playback at startIndex. No-op if
     * the controller has not connected yet.
     *
     * Public surface stays non-suspending so Compose lambdas can call it directly. Internally
     * the function persists the new queue state, then launches a coroutine that awaits the
     * current track's prefetch into ThumpData's on-disk store before handing the items to the
     * MediaController. ThumpData's DataSource is cache-only — if we let ExoPlayer's first
     * `open()` run on an uncached track it would throw IOException synchronously and crash the
     * playback path. The prefetch await closes that window. Lookahead for tracks 2..N is still
     * driven reactively by the playback service's `onMediaItemTransition` / `onTimelineChanged`
     * listener; this gating is only for the very first track of a new queue.
     *
     * Persistence happens BEFORE the prefetch await so a cold relaunch on the same queue picks
     * up the right state even if the await is cancelled (release() called mid-prefetch) before
     * setMediaItems runs.
     */
    fun playQueue(items: List<PlaybackQueueItem>, startIndex: Int, source: PlaybackSource?) {
        val controller: MediaController? = mediaController
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

        val mediaItems: ArrayList<MediaItem> = ArrayList<MediaItem>(items.size)
        val itemCount: Int = items.size
        for (itemIndex in 0 until itemCount) {
            val trackUri: String = "thump://track/" + items[itemIndex].trackId
            mediaItems.add(MediaItem.fromUri(trackUri))
        }

        // Persist immediately so a cold launch on Auto picks up the same state, even if the
        // prefetch await never resolves into the player. Subsequent transitions and position
        // ticks are persisted by the service.
        persistCurrentQueueState(safeStartIndex, positionMs = 0L)

        val currentTrackId: String = items[safeStartIndex].trackId
        playbackCoroutineScope.launch {
            var prefetchSucceeded: Boolean = false
            var prefetchFailureReason: String? = null
            try {
                thumpDataForPrefetch.prefetchAudio(currentTrackId)
                prefetchSucceeded = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (notConfigured: ThumpDataNotConfigured) {
                // No active protocol — surface a non-playing state so the UI shows the track
                // selection but does not pretend playback started.
                val ignoredNotConfigured: ThumpDataNotConfigured = notConfigured
                prefetchFailureReason = UNAVAILABLE_REASON_NOT_CONFIGURED
            } catch (cacheMissOrTransport: IOException) {
                // Offline + not cached, or download failure. Same UX as above.
                prefetchFailureReason = classifyPrefetchIoFailure(cacheMissOrTransport)
            }
            if (prefetchSucceeded) {
                controller.setMediaItems(mediaItems, safeStartIndex, 0L)
                controller.prepare()
                controller.playWhenReady = true
                publishNowPlayingFor(safeStartIndex, isPlayingHint = true, unavailableReason = null)
            } else {
                val failureMessage: String
                if (prefetchFailureReason == null) {
                    failureMessage = UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE
                } else {
                    failureMessage = prefetchFailureReason
                }
                publishNowPlayingFor(
                    safeStartIndex,
                    isPlayingHint = false,
                    unavailableReason = failureMessage,
                )
                // playbackCoroutineScope runs on Dispatchers.Main.immediate, so Toast.makeText
                // can be invoked directly without an extra dispatch.
                Toast.makeText(resolvedApplicationContext, failureMessage, Toast.LENGTH_LONG).show()
            }
        }
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
        // Cancel any in-flight prefetch-then-load so it cannot resume into a released
        // MediaController and try to drive a dead session.
        playbackCoroutineScope.cancel()
        val controller: MediaController? = mediaController
        if (controller != null) {
            controller.removeListener(playerListener)
            controller.release()
            mediaController = null
        }
        val pending: ListenableFuture<MediaController>? = pendingControllerFuture
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
        publishNowPlayingFor(
            newIndex,
            isPlayingHint = controller.isPlaying,
            unavailableReason = null,
        )
    }

    private fun persistCurrentQueueState(currentIndex: Int, positionMs: Long) {
        val persistedItems = ArrayList<PersistedItem>(currentQueueMetadata.size)
        val itemCount = currentQueueMetadata.size
        for (itemIndex in 0 until itemCount) {
            val item = currentQueueMetadata[itemIndex]
            persistedItems.add(
                PersistedItem(
                    trackId = item.trackId,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    coverArtId = item.coverArtId,
                )
            )
        }
        val persistedSource: PersistedSource?
        val source = currentQueueSource
        if (source == null) {
            persistedSource = null
        } else {
            persistedSource = PersistedSource(kind = source.kind.name, name = source.name)
        }
        persistence.save(
            PersistedPlaybackState(
                items = persistedItems,
                currentIndex = currentIndex,
                positionMs = positionMs,
                source = persistedSource,
            )
        )
    }

    private fun publishNowPlayingFor(
        index: Int,
        isPlayingHint: Boolean,
        unavailableReason: String?,
    ) {
        if (index < 0 || index >= currentQueueMetadata.size) {
            return
        }
        val item: PlaybackQueueItem = currentQueueMetadata[index]
        nowPlayingFlow.value = NowPlaying(
            trackId = item.trackId,
            title = item.title,
            artist = item.artist,
            album = item.album,
            coverArtId = item.coverArtId,
            isPlaying = isPlayingHint,
            source = currentQueueSource,
            unavailableReason = unavailableReason,
        )
    }

    /**
     * Apply an unavailable-reason update broadcast by the service. The service stamps the
     * failing trackId into the args bundle so we can publish the reason against the right row
     * in the queue even if the player's currentMediaItemIndex has already moved on by the time
     * the IPC arrives. Missing or unknown trackId falls back to the controller's current index
     * (best effort — keeps the UI honest about *some* unavailable state rather than dropping
     * the message on the floor).
     */
    private fun handleUnavailableReasonCommand(args: Bundle) {
        val reason: String?
        val rawReason: String? = args.getString(SESSION_COMMAND_ARG_REASON)
        if (rawReason == null || rawReason.isEmpty()) {
            reason = UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE
        } else {
            reason = rawReason
        }
        val trackId: String? = args.getString(SESSION_COMMAND_ARG_TRACK_ID)
        val targetIndex: Int
        if (trackId == null || trackId.isEmpty()) {
            targetIndex = currentControllerMediaItemIndex()
        } else {
            val matchedIndex: Int = indexOfTrackId(trackId)
            if (matchedIndex < 0) {
                targetIndex = currentControllerMediaItemIndex()
            } else {
                targetIndex = matchedIndex
            }
        }
        if (targetIndex < 0) {
            return
        }
        publishNowPlayingFor(
            targetIndex,
            isPlayingHint = false,
            unavailableReason = reason,
        )
    }

    private fun currentControllerMediaItemIndex(): Int {
        val controller: MediaController? = mediaController
        if (controller == null) {
            return -1
        }
        return controller.currentMediaItemIndex
    }

    private fun indexOfTrackId(trackId: String): Int {
        val itemCount: Int = currentQueueMetadata.size
        for (itemIndex in 0 until itemCount) {
            if (currentQueueMetadata[itemIndex].trackId == trackId) {
                return itemIndex
            }
        }
        return -1
    }

    // The prefetch IOException carries the protocol's underlying message. We cannot tell
    // "offline + uncached" from a generic transport error with certainty, but the offline-mode
    // message branch in ThumpData stamps "offline" into the text, so a substring check picks
    // it up without coupling to a stable error type.
    private fun classifyPrefetchIoFailure(failure: IOException): String {
        val rawMessage: String? = failure.message
        if (rawMessage == null) {
            return UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE
        }
        if (rawMessage.contains("offline", ignoreCase = true)) {
            return UNAVAILABLE_REASON_OFFLINE
        }
        return UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE
    }

    companion object {
        // Pressing previous within this many ms of the start of a track moves to the previous
        // track. After this, previous restarts the current track. Standard media-app convention.
        private const val PREVIOUS_RESTART_THRESHOLD_MS: Long = 3000L

        const val UNAVAILABLE_REASON_OFFLINE: String = "Not available offline"
        const val UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE: String = "Could not load track"
        const val UNAVAILABLE_REASON_NOT_CONFIGURED: String = "Server not configured"

        // Service → controller custom-command channel for the auto-advance / skip cache-miss
        // path. The playback service and the MediaController bind to the same MediaSession but
        // live in separate processes, so the service has no direct handle on this controller's
        // nowPlayingFlow. It broadcasts a SessionCommand carrying the failing trackId and a
        // human-readable reason; controllerListener.onCustomCommand parses it and routes to
        // publishNowPlayingFor. The action name is namespaced so it cannot collide with any
        // Media3 built-in command.
        const val SESSION_COMMAND_UNAVAILABLE_REASON: String = "com.therobm.thump.session.UNAVAILABLE_REASON"
        const val SESSION_COMMAND_ARG_TRACK_ID: String = "trackId"
        const val SESSION_COMMAND_ARG_REASON: String = "reason"
    }
}

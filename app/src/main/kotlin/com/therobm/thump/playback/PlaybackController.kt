package com.therobm.thump.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
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
class PlaybackController(applicationContext: Context, thumpData: ThumpData) {

    private val resolvedApplicationContext: Context = applicationContext.applicationContext
    private val persistence: PlaybackPersistence = PlaybackPersistence(resolvedApplicationContext)
    // ThumpData is no longer required for prefetch in this class — the service drives all
    // prefetch decisions. Retained on the constructor signature so MainActivity wiring does
    // not need to change; the reference is kept here in case a future surface (e.g. retry from
    // a disabled UI) wants to cross-check cache state before issuing playback calls.
    private val thumpDataForRetry: ThumpData = thumpData

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

    // Custom-command channel the playback service uses to push the unavailable-track reason
    // string across the process boundary. NowPlaying lives in this (UI) process inside
    // nowPlayingFlow, and the service cannot mutate it directly. The service's auto-advance
    // failure path broadcasts a SessionCommand carrying the failing trackId and a human-readable
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
     * Synchronous: builds MediaItems, persists, then drives setMediaItems / prepare /
     * playWhenReady=true straight through to the MediaController. No prefetch gate. If the
     * current track's audio isn't on disk, ExoPlayer's first open() raises IOException and the
     * service's onPlayerError recovery (prefetch-then-prepare, with auto-advance fallback)
     * handles it.
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
            val mediaItemId: String = "thump-track/" + items[itemIndex].trackId
            val mediaItem: MediaItem = MediaItem.Builder()
                .setMediaId(mediaItemId)
                .setUri(trackUri)
                .build()
            mediaItems.add(mediaItem)
        }

        // Persist immediately so a cold launch on Auto picks up the same state even if the
        // process dies before the service has a chance to write its own position update.
        persistCurrentQueueState(safeStartIndex, positionMs = 0L)

        controller.setMediaItems(mediaItems, safeStartIndex, 0L)
        controller.prepare()
        controller.playWhenReady = true
        publishNowPlayingFor(safeStartIndex, isPlayingHint = true, unavailableReason = null)
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

    /**
     * Retry the currently-loaded track after the UI has shown an unavailable banner. Clears the
     * banner from NowPlaying and asks the player to re-prepare the current MediaItem.
     *
     * Media3 routes `MediaController.prepare()` through the session to the underlying ExoPlayer,
     * which re-runs source preparation on whatever the current media item is. If the cache is
     * still missing, the service's onPlayerError path fires again (so a second tap of retry
     * while offline just keeps showing the banner). If the cache is now populated — typically
     * because connectivity returned and the lookahead prefetched the track in the meantime — the
     * load succeeds and playback resumes from the user's existing playWhenReady intent.
     */
    fun retryCurrentTrack() {
        val controller: MediaController? = mediaController
        if (controller == null) {
            return
        }
        val current: NowPlaying? = nowPlayingFlow.value
        if (current != null) {
            nowPlayingFlow.value = current.copy(unavailableReason = null)
        }
        controller.prepare()
        controller.playWhenReady = true
    }

    fun release() {
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
        val current: NowPlaying? = nowPlayingFlow.value
        if (current == null) {
            return
        }
        // Track actually started playing — by definition the load succeeded, so any pending
        // unavailable banner is no longer accurate and should be wiped.
        val nextReason: String?
        if (isPlaying) {
            nextReason = null
        } else {
            nextReason = current.unavailableReason
        }
        if (current.isPlaying == isPlaying && current.unavailableReason == nextReason) {
            return
        }
        nowPlayingFlow.value = current.copy(isPlaying = isPlaying, unavailableReason = nextReason)
    }

    private fun refreshNowPlayingFromCurrentIndex() {
        val controller: MediaController? = mediaController
        if (controller == null) {
            return
        }
        val newIndex: Int = controller.currentMediaItemIndex
        // Persistence rule: an unavailable banner stays visible across every intermediate
        // transition (auto-advance from one uncached track to the next, manual skip while
        // offline, etc.) until either (a) the player actually starts playing — i.e. the new
        // track loaded successfully — or (b) retryCurrentTrack() is called. The service's
        // unavailable-reason broadcast replaces the banner authoritatively whenever a new
        // failure is identified, and the onIsPlayingChanged path below wipes it when isPlaying
        // becomes true (track is genuinely loaded). Carrying the previous reason here even
        // across trackId changes prevents the banner from flickering off during a cluster of
        // failed auto-advances.
        val previous: NowPlaying? = nowPlayingFlow.value
        val carriedReason: String?
        if (previous == null) {
            carriedReason = null
        } else {
            carriedReason = previous.unavailableReason
        }
        publishNowPlayingFor(
            newIndex,
            isPlayingHint = controller.isPlaying,
            unavailableReason = carriedReason,
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
     *
     * A missing or empty reason is a "clear" signal — the service sends one of these after a
     * recovery prefetch succeeds so the transient LOADING banner is wiped before playback
     * actually starts. Carries through as `unavailableReason = null` on the NowPlaying flow.
     */
    private fun handleUnavailableReasonCommand(args: Bundle) {
        val rawReason: String? = args.getString(SESSION_COMMAND_ARG_REASON)
        val resolvedReason: String?
        if (rawReason == null || rawReason.isEmpty()) {
            resolvedReason = null
        } else {
            resolvedReason = rawReason
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
            unavailableReason = resolvedReason,
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

    companion object {
        // Pressing previous within this many ms of the start of a track moves to the previous
        // track. After this, previous restarts the current track. Standard media-app convention.
        private const val PREVIOUS_RESTART_THRESHOLD_MS: Long = 3000L

        const val UNAVAILABLE_REASON_OFFLINE: String = "Not available offline — tap play to retry"
        const val UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE: String = "Could not load track — tap play to retry"
        const val UNAVAILABLE_REASON_NOT_CONFIGURED: String = "Server not configured"

        // Transient marker the service publishes during onPlayerError recovery (cache-miss
        // prefetch in progress). The UI branches on this exact string to render a progress
        // indicator instead of a play/retry icon and to use the neutral banner colour rather
        // than the failure-state muted-red. Held as a constant so the service and the UI share
        // a single source of truth for the value.
        const val UNAVAILABLE_REASON_LOADING: String = "Loading…"

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

package com.therobm.thump.playback

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.therobm.thump.data.ThumpData
import com.therobm.thump.data.ThumpDataNotConfigured
import com.therobm.thump.settings.ThumpSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val POSITION_TICK_INTERVAL_MS: Long = 5000L
private const val SCROBBLE_SUBMISSION_TIME_THRESHOLD_MS: Long = 4L * 60L * 1000L

/**
 * Hosts the ExoPlayer plus the MediaLibrarySession that backs both the app's mini player and
 * Android Auto's media browser. Owns playback persistence (queue + index + position) and
 * Subsonic scrobble: submission=false on track start, submission=true at 50%/4min.
 *
 * Resume-on-launch: on onCreate we restore the player's last persisted queue and seek to the
 * saved position before any controller binds, so Auto's now-playing surfaces the resumed
 * track immediately instead of an empty session.
 *
 * Audio bytes flow through ThumpData's DataSource implementation. MediaItems carry stable
 * `thump://track/<id>` URIs; ExoPlayer's MediaSource.Factory is wired to a DataSource.Factory
 * that returns this process's ThumpData instance.
 */
class ThumpPlaybackService : MediaLibraryService() {

    private var librarySession: MediaLibrarySession? = null
    private val serviceCoroutineScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    private val persistence: PlaybackPersistence by lazy {
        PlaybackPersistence(applicationContext)
    }
    private val settings: ThumpSettings by lazy {
        ThumpSettings(applicationContext)
    }

    // Scrobble state for the currently-playing track. Reset on every media-item transition.
    private var currentScrobbleTrackId: String? = null
    private var hasSubmittedCurrent: Boolean = false

    // Service-process ThumpData. Per Projects/Thump.md, MediaLibraryService eventually runs in
    // its own process; each process gets its own ThumpData against the shared SQLite WAL +
    // blob directory. ExoPlayer reads audio bytes through this instance via the DataSource
    // interface ThumpData implements.
    private var serviceThumpData: ThumpData? = null

    // Active prefetch jobs keyed by trackId. Lookahead launches a job per track in the window;
    // jobs are never cancelled by window movement — a launched download runs to completion so a
    // user who skips forward then back doesn't re-pay for bytes that were almost on disk. We
    // still track jobs so a stale window doesn't double-launch the same trackId. Entries are
    // removed when the Job completes. Touched from Main (Player.Listener) and from the prefetch
    // IO scope (invokeOnCompletion), so guarded by `prefetchJobsLock`. ThumpData itself
    // coalesces concurrent prefetch calls per trackId, so any leak here only ever pays for one
    // network download.
    private val prefetchJobsLock: Any = Any()
    private val prefetchJobsByTrackId: HashMap<String, Job> = HashMap<String, Job>()

    // TrackIds that have already produced a user-visible failure toast this service lifetime.
    // Pruned on each lookahead refresh: ids not in the current window are removed so a user who
    // skips past a failed track and later returns to it gets a fresh toast. Also cleared per-id
    // on a later prefetch success.
    private val toastedFailureTrackIds: HashSet<String> = HashSet<String>()

    override fun onCreate() {
        super.onCreate()
        val thumpDataForProcess: ThumpData = ThumpData(applicationContext)
        serviceThumpData = thumpDataForProcess
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val dataSourceFactory: DataSource.Factory = DataSource.Factory {
            // ExoPlayer's contract permits returning the same DataSource instance — ThumpData
            // is process-wide and tracks its own per-open state, so handing the singleton back
            // here is safe.
            thumpDataForProcess
        }
        val mediaSourceFactory: DefaultMediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player.addListener(buildPlayerListener(player))

        val callback = ThumpMediaLibraryCallback(
            applicationCoroutineScope = serviceCoroutineScope,
            thumpData = thumpDataForProcess,
            applicationPackageName = applicationContext.packageName,
        )
        librarySession = MediaLibrarySession.Builder(this, player, callback).build()

        restorePersistedStateInto(player)
        startPositionPersistenceLoop(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return librarySession
    }

    override fun onDestroy() {
        cancelAllPrefetchJobs()
        val sessionSnapshot = librarySession
        if (sessionSnapshot != null) {
            // Final position write so the next launch starts where this session ended.
            persistCurrentPlayerState(sessionSnapshot.player)
            sessionSnapshot.player.release()
            sessionSnapshot.release()
            librarySession = null
        }
        serviceCoroutineScope.cancel()
        super.onDestroy()
    }

    private fun buildPlayerListener(player: Player): Player.Listener {
        return object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val trackId: String? = extractTrackId(mediaItem)
                Log.d("ThumpRecovery", "onMediaItemTransition reason=" + reason
                    + " newTrackId=" + extractTrackId(mediaItem))
                currentScrobbleTrackId = trackId
                hasSubmittedCurrent = false
                if (trackId != null) {
                    fireScrobbleNowPlaying(trackId)
                }
                persistCurrentPlayerState(player)
                refreshAudioPrefetchWindow(player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // Catches the queue-set case where the listener is bound after the player
                // already has items (restore-on-launch) and the case where the queue is
                // replaced wholesale without the current track changing identity.
                Log.d("ThumpRecovery", "onTimelineChanged reason=" + reason
                    + " itemCount=" + timeline.windowCount)
                val ignoredTimeline: Timeline = timeline
                val ignoredReason: Int = reason
                refreshAudioPrefetchWindow(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause: Throwable? = error.cause
                val causeClassName: String
                if (cause == null) {
                    causeClassName = "null"
                } else {
                    causeClassName = cause.javaClass.name
                }
                Log.d("ThumpRecovery", "onPlayerError fired: errorCode=" + error.errorCode + " message=" + error.message + " causeClass=" + causeClassName)
                handleCacheMissPlayerError(player, error)
            }
        }
    }

    /**
     * Recovery on cache-miss IOException at the DataSource layer.
     *
     * The flow:
     *   1. If the error is not our "audio blob not cached" signature, leave it for ExoPlayer's
     *      existing error path.
     *   2. Broadcast UNAVAILABLE_REASON_LOADING so the UI shows a transient loading indicator
     *      instead of leaving the user staring at a frozen mini player.
     *   3. Launch a prefetch for the failed trackId. On success — if the player is still on the
     *      same MediaItem, call player.prepare() and force playWhenReady=true. onPlayerError only
     *      fires when the player was already attempting to load (playWhenReady was already true),
     *      so re-asserting it preserves user intent and avoids the prepared-but-paused state.
     *      Then clear the loading banner.
     *   4. On prefetch failure — try to auto-advance forward to the next cached track. If none
     *      exists, pause the player as a terminal state and surface the unavailable banner.
     *
     * Recovery is intentionally non-reentrant per-error; the next onPlayerError invocation drives
     * its own recovery if the new current is also broken.
     */
    private fun handleCacheMissPlayerError(
        player: Player,
        error: PlaybackException,
    ): Unit {
        val matchedTrackId: String? = extractCacheMissTrackId(error)
        Log.d("ThumpRecovery", "extractCacheMissTrackId result: " + matchedTrackId)
        if (matchedTrackId == null) {
            return
        }
        val resolvedTrackId: String
        if (matchedTrackId.isEmpty()) {
            val fallbackTrackId: String? = extractTrackId(player.currentMediaItem)
            if (fallbackTrackId == null) {
                return
            }
            resolvedTrackId = fallbackTrackId
        } else {
            resolvedTrackId = matchedTrackId
        }
        val thumpDataInstance: ThumpData? = serviceThumpData
        if (thumpDataInstance == null) {
            return
        }
        Log.d("ThumpRecovery", "launching recovery for trackId=" + resolvedTrackId)
        serviceCoroutineScope.launch {
            withContext(Dispatchers.Main) {
                broadcastUnavailableReason(
                    resolvedTrackId,
                    PlaybackController.UNAVAILABLE_REASON_LOADING,
                )
            }
            Log.d("ThumpRecovery", "broadcast LOADING for trackId=" + resolvedTrackId + ", about to call prefetchAudio")
            var prefetchSucceeded: Boolean = false
            var prefetchFailureReason: String? = null
            try {
                Log.d("ThumpRecovery", "calling prefetchAudio trackId=" + resolvedTrackId)
                thumpDataInstance.prefetchAudio(resolvedTrackId)
                Log.d("ThumpRecovery", "prefetchAudio returned success trackId=" + resolvedTrackId)
                prefetchSucceeded = true
            } catch (cancellation: CancellationException) {
                Log.d("ThumpRecovery", "prefetchAudio threw " + cancellation.javaClass.simpleName + ": " + cancellation.message + " trackId=" + resolvedTrackId)
                throw cancellation
            } catch (notConfigured: ThumpDataNotConfigured) {
                Log.d("ThumpRecovery", "prefetchAudio threw " + notConfigured.javaClass.simpleName + ": " + notConfigured.message + " trackId=" + resolvedTrackId)
                val ignoredNotConfigured: ThumpDataNotConfigured = notConfigured
                prefetchFailureReason = PlaybackController.UNAVAILABLE_REASON_NOT_CONFIGURED
            } catch (cacheMissOrTransport: IOException) {
                Log.d("ThumpRecovery", "prefetchAudio threw " + cacheMissOrTransport.javaClass.simpleName + ": " + cacheMissOrTransport.message + " trackId=" + resolvedTrackId)
                prefetchFailureReason = classifyRestoreIoFailure(cacheMissOrTransport)
            }
            withContext(Dispatchers.Main) {
                if (prefetchSucceeded) {
                    toastedFailureTrackIds.remove(resolvedTrackId)
                    // The blob is on disk now — regardless of whether the user is still on this
                    // track, the LOADING state for this trackId is no longer valid. Clear it.
                    broadcastUnavailableReasonClear(resolvedTrackId)
                    val stillCurrentTrackId: String? = extractTrackId(player.currentMediaItem)
                    val playerIsIdle: Boolean = player.playbackState == Player.STATE_IDLE
                    Log.d("ThumpRecovery", "post-prefetch trackId=" + resolvedTrackId
                        + " playerIsIdle=" + playerIsIdle
                        + " playbackState=" + player.playbackState
                        + " currentMediaItemTrackId=" + stillCurrentTrackId)
                    if (playerIsIdle) {
                        player.prepare()
                        player.playWhenReady = true
                        Log.d("ThumpRecovery", "called prepare() + playWhenReady=true trackId=" + resolvedTrackId)
                        Log.d("ThumpRecovery", "post-recovery refreshing lookahead trackId=" + resolvedTrackId)
                        refreshAudioPrefetchWindow(player)
                    }
                } else {
                    val failureMessage: String
                    if (prefetchFailureReason == null) {
                        failureMessage = PlaybackController.UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE
                    } else {
                        failureMessage = prefetchFailureReason
                    }
                    attemptAutoAdvanceToNextCachedTrack(
                        player = player,
                        failedTrackId = resolvedTrackId,
                        failureMessage = failureMessage,
                    )
                }
            }
        }
    }

    /**
     * Walk forward from `currentMediaItemIndex + 1` looking for a track whose audio blob is
     * already on disk. First hit: seek to it, return — the transition triggers a fresh load.
     * No hit: nothing playable ahead, so pause the player (terminal state, not a recovery one)
     * and broadcast unavailable so the UI surfaces the banner.
     *
     * Must run on Main (Player access).
     */
    private fun attemptAutoAdvanceToNextCachedTrack(
        player: Player,
        failedTrackId: String,
        failureMessage: String,
    ): Unit {
        Log.d("ThumpRecovery", "auto-advance from failedTrackId=" + failedTrackId + " itemCount=" + player.mediaItemCount + " currentIndex=" + player.currentMediaItemIndex)
        val thumpDataInstance: ThumpData? = serviceThumpData
        if (thumpDataInstance == null) {
            haltOnUnplayable(player, failedTrackId, failureMessage)
            return
        }
        val itemCount: Int = player.mediaItemCount
        val startSearchIndex: Int = player.currentMediaItemIndex + 1
        var seekedToIndex: Int = -1
        for (candidateIndex in startSearchIndex until itemCount) {
            val candidateItem: MediaItem = player.getMediaItemAt(candidateIndex)
            val candidateTrackId: String? = extractTrackId(candidateItem)
            if (candidateTrackId == null) {
                continue
            }
            val isCached: Boolean = thumpDataInstance.isAudioBlobCached(candidateTrackId)
            if (isCached) {
                Log.d("ThumpRecovery", "auto-advance seeking to index=" + candidateIndex + " trackId=" + candidateTrackId)
                player.seekTo(candidateIndex, 0L)
                seekedToIndex = candidateIndex
                break
            }
        }
        if (seekedToIndex >= 0) {
            return
        }
        haltOnUnplayable(player, failedTrackId, failureMessage)
    }

    private fun haltOnUnplayable(
        player: Player,
        failedTrackId: String,
        failureMessage: String,
    ): Unit {
        Log.d("ThumpRecovery", "halt on unplayable trackId=" + failedTrackId + " reason=" + failureMessage)
        player.playWhenReady = false
        val shouldToast: Boolean
        if (toastedFailureTrackIds.contains(failedTrackId)) {
            shouldToast = false
        } else {
            toastedFailureTrackIds.add(failedTrackId)
            shouldToast = true
        }
        if (shouldToast) {
            Toast.makeText(
                applicationContext,
                failureMessage,
                Toast.LENGTH_LONG,
            ).show()
        }
        broadcastUnavailableReason(failedTrackId, failureMessage)
    }

    /**
     * Cross-process push of the unavailable reason to every connected MediaController. The
     * controller in the UI process owns the NowPlaying StateFlow and surfaces the banner; this
     * is the only IPC path that carries the reason string. Toast fires service-side (single
     * source of truth for ExoPlayer state) so we do not double up across processes.
     */
    private fun broadcastUnavailableReason(trackId: String, reason: String): Unit {
        val sessionSnapshot: MediaSession? = librarySession
        if (sessionSnapshot == null) {
            return
        }
        val args: Bundle = Bundle()
        args.putString(PlaybackController.SESSION_COMMAND_ARG_TRACK_ID, trackId)
        args.putString(PlaybackController.SESSION_COMMAND_ARG_REASON, reason)
        val command: SessionCommand = SessionCommand(
            PlaybackController.SESSION_COMMAND_UNAVAILABLE_REASON,
            Bundle.EMPTY,
        )
        sessionSnapshot.broadcastCustomCommand(command, args)
    }

    /**
     * Companion to broadcastUnavailableReason: signal the controller that the previously-flagged
     * trackId is no longer in an unavailable state, so the banner can be wiped. Sent after the
     * recovery prefetch succeeds. The reason key is omitted from the args bundle; the controller
     * treats a missing/empty reason as "clear".
     */
    private fun broadcastUnavailableReasonClear(trackId: String): Unit {
        val sessionSnapshot: MediaSession? = librarySession
        if (sessionSnapshot == null) {
            return
        }
        val args: Bundle = Bundle()
        args.putString(PlaybackController.SESSION_COMMAND_ARG_TRACK_ID, trackId)
        val command: SessionCommand = SessionCommand(
            PlaybackController.SESSION_COMMAND_UNAVAILABLE_REASON,
            Bundle.EMPTY,
        )
        sessionSnapshot.broadcastCustomCommand(command, args)
    }

    /**
     * Walk a PlaybackException's cause chain for our cache-miss IOException signature. Returns
     * the trackId portion of the message ("trackId=<id>") on a hit, an empty string when the
     * signature matched but the id was unparseable (caller substitutes the player's current
     * id), or null when this is not a cache-miss error and we should leave it alone.
     */
    private fun extractCacheMissTrackId(error: PlaybackException): String? {
        val marker: String = "ThumpData: audio blob not cached for trackId="
        val maxCauseDepth: Int = 32
        var cursor: Throwable? = error
        for (depth in 0 until maxCauseDepth) {
            val current: Throwable? = cursor
            if (current == null) {
                return null
            }
            val message: String? = current.message
            if (message != null && message.contains(marker)) {
                val afterMarker: String = message.substringAfter(marker)
                // The message format is "...trackId=<id> — playback service must..."; cut at
                // the first whitespace so the dash separator does not leak into the id.
                val trackIdRaw: String = afterMarker.substringBefore(' ')
                return trackIdRaw
            }
            cursor = current.cause
        }
        return null
    }

    /**
     * Restore the persisted queue + position into the player on service startup. Synchronous: no
     * blocking prefetch. setMediaItems / prepare / playWhenReady=false run immediately so the
     * Now Playing surface populates without waiting on disk. If the current track's audio body
     * isn't cached, ExoPlayer's first open() will throw and onPlayerError drives the cache-miss
     * recovery path the same way every other miss does.
     */
    private fun restorePersistedStateInto(player: Player) {
        val persisted: PersistedPlaybackState? = persistence.load()
        if (persisted == null) {
            return
        }
        if (persisted.items.isEmpty()) {
            return
        }
        val mediaItems: ArrayList<MediaItem> = ArrayList<MediaItem>(persisted.items.size)
        val itemCount: Int = persisted.items.size
        for (itemIndex in 0 until itemCount) {
            val item: PersistedItem = persisted.items[itemIndex]
            mediaItems.add(buildMediaItemFromPersisted(item))
        }
        val safeIndex: Int
        if (persisted.currentIndex < 0) {
            safeIndex = 0
        } else if (persisted.currentIndex >= mediaItems.size) {
            safeIndex = mediaItems.size - 1
        } else {
            safeIndex = persisted.currentIndex
        }
        val restorePositionMs: Long = persisted.positionMs
        val currentTrackId: String = persisted.items[safeIndex].trackId
        player.setMediaItems(mediaItems, safeIndex, restorePositionMs)
        player.prepare()
        player.playWhenReady = false
        currentScrobbleTrackId = currentTrackId
        hasSubmittedCurrent = false
    }

    private fun classifyRestoreIoFailure(failure: IOException): String {
        val rawMessage: String? = failure.message
        if (rawMessage == null) {
            return PlaybackController.UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE
        }
        if (rawMessage.contains("offline", ignoreCase = true)) {
            return PlaybackController.UNAVAILABLE_REASON_OFFLINE
        }
        return PlaybackController.UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE
    }

    private fun buildMediaItemFromPersisted(item: PersistedItem): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(item.title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (item.artist.isNotEmpty()) {
            metadataBuilder.setArtist(item.artist)
        }
        if (item.album != null) {
            metadataBuilder.setAlbumTitle(item.album)
        }
        // Lock-screen / notification artwork is intentionally not set here — the in-app mini
        // player and Now Playing screen render cover art via ArtImage from ThumpData. Setting
        // setArtworkUri for system surfaces (content:// ContentProvider URIs) is part of the
        // Android Auto port follow-up, which uses the cover-art ContentProvider end-to-end.
        val trackUri: String = "thump://track/" + item.trackId
        return MediaItem.Builder()
            .setMediaId("thump-track/" + item.trackId)
            .setUri(trackUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /**
     * Ticker that runs while the service is alive. Persists the current player position and
     * checks the scrobble-submission threshold (50% of duration, or 4 minutes, whichever
     * first). Sleeps in 5s slices so the wakelock cost is negligible.
     *
     * Must run on the main thread because Player.isPlaying / currentPosition / etc. enforce
     * the application looper. The actual scrobble HTTP fires from within
     * fireScrobble* helpers which launch their own coroutines on the IO-default service scope.
     */
    private fun startPositionPersistenceLoop(player: Player) {
        serviceCoroutineScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(POSITION_TICK_INTERVAL_MS)
                if (!isActive) {
                    break
                }
                if (player.isPlaying) {
                    persistCurrentPlayerState(player)
                    maybeFireScrobbleSubmission(player)
                }
            }
        }
    }

    private fun maybeFireScrobbleSubmission(player: Player) {
        if (hasSubmittedCurrent) {
            return
        }
        val trackId = currentScrobbleTrackId
        if (trackId == null) {
            return
        }
        val position = player.currentPosition
        val duration = player.duration
        val threshold: Long
        if (duration <= 0L) {
            threshold = SCROBBLE_SUBMISSION_TIME_THRESHOLD_MS
        } else {
            val halfDuration = duration / 2L
            if (halfDuration < SCROBBLE_SUBMISSION_TIME_THRESHOLD_MS) {
                threshold = halfDuration
            } else {
                threshold = SCROBBLE_SUBMISSION_TIME_THRESHOLD_MS
            }
        }
        if (position < threshold) {
            return
        }
        hasSubmittedCurrent = true
        fireScrobbleSubmission(trackId)
    }

    private fun fireScrobbleNowPlaying(trackId: String) {
        if (!settings.getScrobbleEnabled()) {
            return
        }
        val thumpDataInstance: ThumpData? = serviceThumpData
        if (thumpDataInstance == null) {
            return
        }
        val firedAtEpochMillis: Long = System.currentTimeMillis()
        serviceCoroutineScope.launch {
            try {
                thumpDataInstance.scrobble(trackId, firedAtEpochMillis, false)
            } catch (scrobbleFailure: IOException) {
                // Best-effort signal — server unreachable or offline mode rejected the call.
            } catch (notConfigured: ThumpDataNotConfigured) {
                // No server configured yet — nothing to scrobble to.
            }
        }
    }

    private fun fireScrobbleSubmission(trackId: String) {
        if (!settings.getScrobbleEnabled()) {
            return
        }
        val thumpDataInstance: ThumpData? = serviceThumpData
        if (thumpDataInstance == null) {
            return
        }
        val firedAtEpochMillis: Long = System.currentTimeMillis()
        serviceCoroutineScope.launch {
            try {
                thumpDataInstance.scrobble(trackId, firedAtEpochMillis, true)
            } catch (scrobbleFailure: IOException) {
                // Best-effort signal — server unreachable or offline mode rejected the call.
            } catch (notConfigured: ThumpDataNotConfigured) {
                // No server configured yet — nothing to scrobble to.
            }
        }
    }

    private fun persistCurrentPlayerState(player: Player) {
        val itemCount = player.mediaItemCount
        if (itemCount <= 0) {
            persistence.clear()
            return
        }
        val previous = persistence.load()
        // We need title/artist/album/trackId/coverArtId to round-trip. The player only knows
        // titles via MediaMetadata; trackId and coverArtId are held in the previously persisted
        // blob (or were just written by the app's PlaybackController on playQueue). Fall back to
        // whatever's there; the player's setMediaItems was driven by that blob in the first
        // place when the service restored.
        if (previous == null) {
            // No prior blob means the app called playQueue before we ran. PlaybackController
            // wrote the blob then; we shouldn't be here without one, but guard anyway.
            return
        }
        val updatedIndex: Int
        val playerIndex = player.currentMediaItemIndex
        if (playerIndex < 0) {
            updatedIndex = 0
        } else if (playerIndex >= previous.items.size) {
            updatedIndex = previous.items.size - 1
        } else {
            updatedIndex = playerIndex
        }
        val positionMs: Long
        val rawPosition = player.currentPosition
        if (rawPosition < 0L) {
            positionMs = 0L
        } else {
            positionMs = rawPosition
        }
        persistence.save(
            PersistedPlaybackState(
                items = previous.items,
                currentIndex = updatedIndex,
                positionMs = positionMs,
                source = previous.source,
            )
        )
    }

    /**
     * Recompute the prefetch lookahead window after the queue or current track changed.
     *
     * The window is `[currentIndex, currentIndex + lookahead)` clamped to the queue's bounds,
     * with `lookahead` read from `ThumpSettings.getPrefetchLookahead()` (default 10). Tracks
     * inside the window get a supervised prefetch Job launched against the service scope if
     * one is not already in flight. There is no cancel side — once a prefetch is launched it
     * runs to completion even if the lookahead window slides off it. Cancellation on every
     * skip means a rapid-skip storm tears down work the user already paid for in network bytes;
     * letting downloads finish leaves them on disk for the next time the queue reaches them.
     *
     * Each prefetch runs as an independent child of the service scope (which is backed by a
     * `SupervisorJob`) so one failed prefetch never cancels its siblings. Failures are
     * swallowed inside the Job body — the playback service is not responsible for surfacing
     * cache-miss errors; the onPlayerError recovery path handles user-visible signalling when
     * ExoPlayer actually reaches an uncached track.
     *
     * This call also prunes `toastedFailureTrackIds` of entries outside the window, so a track
     * that previously failed and got skipped past will produce a fresh toast if the user later
     * comes back to it.
     */
    private fun refreshAudioPrefetchWindow(player: Player): Unit {
        val thumpDataInstance: ThumpData? = serviceThumpData
        if (thumpDataInstance == null) {
            return
        }
        val itemCount: Int = player.mediaItemCount
        val currentIndex: Int = player.currentMediaItemIndex
        if (itemCount <= 0 || currentIndex < 0) {
            return
        }
        val lookaheadSetting: Int = settings.getPrefetchLookahead()
        if (lookaheadSetting <= 0) {
            return
        }
        val computedEnd: Long = currentIndex.toLong() + lookaheadSetting.toLong()
        val windowEndExclusive: Int
        if (computedEnd >= itemCount.toLong()) {
            windowEndExclusive = itemCount
        } else {
            windowEndExclusive = computedEnd.toInt()
        }
        Log.d("ThumpRecovery", "refreshAudioPrefetchWindow itemCount=" + itemCount
            + " currentIndex=" + currentIndex
            + " lookaheadSetting=" + lookaheadSetting
            + " windowEndExclusive=" + windowEndExclusive)
        val windowTrackIds: HashSet<String> = collectWindowTrackIds(player, currentIndex, windowEndExclusive)
        pruneToastedFailuresOutsideWindow(windowTrackIds)
        launchPrefetchJobsForWindow(thumpDataInstance, player, currentIndex, windowEndExclusive)
    }

    private fun collectWindowTrackIds(
        player: Player,
        windowStartInclusive: Int,
        windowEndExclusive: Int,
    ): HashSet<String> {
        val windowTrackIds: HashSet<String> = HashSet<String>()
        for (windowIndex in windowStartInclusive until windowEndExclusive) {
            val mediaItemAtIndex: MediaItem = player.getMediaItemAt(windowIndex)
            val trackIdAtIndex: String? = extractTrackId(mediaItemAtIndex)
            if (trackIdAtIndex != null) {
                windowTrackIds.add(trackIdAtIndex)
            }
        }
        return windowTrackIds
    }

    private fun pruneToastedFailuresOutsideWindow(windowTrackIds: HashSet<String>): Unit {
        val staleToastIds: ArrayList<String> = ArrayList<String>()
        for (toastedId in toastedFailureTrackIds) {
            if (!windowTrackIds.contains(toastedId)) {
                staleToastIds.add(toastedId)
            }
        }
        val staleCount: Int = staleToastIds.size
        for (staleIndex in 0 until staleCount) {
            toastedFailureTrackIds.remove(staleToastIds[staleIndex])
        }
    }

    private fun cancelAllPrefetchJobs(): Unit {
        val drainedJobs: ArrayList<Job> = ArrayList<Job>()
        synchronized(prefetchJobsLock) {
            val activeJobs: Collection<Job> = prefetchJobsByTrackId.values
            drainedJobs.addAll(activeJobs)
            prefetchJobsByTrackId.clear()
        }
        val drainedCount: Int = drainedJobs.size
        for (drainedIndex in 0 until drainedCount) {
            drainedJobs[drainedIndex].cancel()
        }
    }

    private fun launchPrefetchJobsForWindow(
        thumpDataInstance: ThumpData,
        player: Player,
        windowStartInclusive: Int,
        windowEndExclusive: Int,
    ): Unit {
        for (windowIndex in windowStartInclusive until windowEndExclusive) {
            val mediaItemAtIndex: MediaItem = player.getMediaItemAt(windowIndex)
            val trackIdAtIndex: String? = extractTrackId(mediaItemAtIndex)
            if (trackIdAtIndex == null) {
                continue
            }
            launchPrefetchJobForTrackIfAbsent(thumpDataInstance, trackIdAtIndex)
        }
    }

    private fun launchPrefetchJobForTrackIfAbsent(
        thumpDataInstance: ThumpData,
        trackId: String,
    ): Unit {
        Log.d("ThumpRecovery", "lookahead consider trackId=" + trackId)
        synchronized(prefetchJobsLock) {
            if (prefetchJobsByTrackId.containsKey(trackId)) {
                Log.d("ThumpRecovery", "lookahead skip (in-flight) trackId=" + trackId)
                return
            }
        }
        Log.d("ThumpRecovery", "lookahead launching trackId=" + trackId)
        val prefetchJob: Job = serviceCoroutineScope.launch {
            try {
                thumpDataInstance.prefetchAudio(trackId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (notConfigured: ThumpDataNotConfigured) {
                // No active protocol — nothing to prefetch from. Siblings are unaffected.
                val ignoredNotConfigured: ThumpDataNotConfigured = notConfigured
            } catch (transportFailure: IOException) {
                // Network or disk failure for this one track. Sibling prefetches continue;
                // the playback path surfaces a cache miss to the user through ExoPlayer's
                // existing error path when (and only when) it actually tries to play this id.
                val ignoredTransportFailure: IOException = transportFailure
            }
        }
        var didInstallJob: Boolean = false
        synchronized(prefetchJobsLock) {
            if (!prefetchJobsByTrackId.containsKey(trackId)) {
                prefetchJobsByTrackId[trackId] = prefetchJob
                didInstallJob = true
            }
        }
        if (!didInstallJob) {
            prefetchJob.cancel()
            return
        }
        prefetchJob.invokeOnCompletion { completionThrowable: Throwable? ->
            val ignoredCompletionThrowable: Throwable? = completionThrowable
            synchronized(prefetchJobsLock) {
                val currentJob: Job? = prefetchJobsByTrackId[trackId]
                if (currentJob === prefetchJob) {
                    prefetchJobsByTrackId.remove(trackId)
                }
            }
        }
    }

    private fun extractTrackId(mediaItem: MediaItem?): String? {
        if (mediaItem == null) {
            return null
        }
        val mediaId = mediaItem.mediaId
        val prefix = "thump-track/"
        if (mediaId.startsWith(prefix)) {
            return mediaId.removePrefix(prefix)
        }
        return null
    }
}

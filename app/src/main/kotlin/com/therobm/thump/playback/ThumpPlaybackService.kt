package com.therobm.thump.playback

import android.os.Bundle
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

    // Active prefetch jobs keyed by trackId. Populated when the queue's lookahead window
    // expands across a track; entries are removed when the underlying Job completes or when
    // the window shifts off that track. Touched from the main thread (Player.Listener
    // callbacks) and from the prefetch IO scope (Job.invokeOnCompletion), so all access is
    // guarded by `prefetchJobsLock`. ThumpData itself coalesces concurrent prefetch calls per
    // trackId so racing entries here only ever produce one network download.
    private val prefetchJobsLock: Any = Any()
    private val prefetchJobsByTrackId: HashMap<String, Job> = HashMap<String, Job>()

    // Tracks the trackIds with an active cache-miss recovery (gate or safety net) so the gate
    // and safety net don't race each other into two parallel recoveries for the same track. The
    // gate fires from onMediaItemTransition; the safety net fires from onPlayerError if
    // ExoPlayer raced ahead and triggered DataSource.open before the gate's prefetch finished.
    // Both end up in `runCacheMissRecovery`; the set entry is the dedup token.
    private val inFlightCacheRecoveryLock: Any = Any()
    private val inFlightCacheRecoveryTrackIds: HashSet<String> = HashSet<String>()

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
                currentScrobbleTrackId = trackId
                hasSubmittedCurrent = false
                if (trackId != null) {
                    fireScrobbleNowPlaying(trackId)
                }
                persistCurrentPlayerState(player)
                // Proactive gate. PLAYLIST_CHANGED transitions are already covered by the
                // cold-start (restorePersistedStateInto) and initial-play (PlaybackController.
                // playQueue) gates, both of which await prefetch before installing items — so
                // re-running the gate here would just produce a duplicate toast on the same
                // failure. AUTO / SEEK / REPEAT transitions are the ones this gate exists for.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    maybeGateCurrentTrackOnCache(player, trackId)
                }
                refreshAudioPrefetchWindow(player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // Catches the queue-set case where the listener is bound after the player
                // already has items (restore-on-launch) and the case where the queue is
                // replaced wholesale without the current track changing identity.
                val ignoredTimeline: Timeline = timeline
                val ignoredReason: Int = reason
                refreshAudioPrefetchWindow(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Safety net for the case where the gate didn't catch the miss — typically
                // when ExoPlayer raced ahead of the onMediaItemTransition callback and called
                // DataSource.open synchronously, getting the "audio blob not cached"
                // IOException. Any other PlaybackException is intentionally left to ExoPlayer's
                // existing error path; we only intervene on our own cache-miss signature.
                handlePlayerErrorForCacheMiss(player, error)
            }
        }
    }

    /**
     * Proactive miss-recovery gate fired from `onMediaItemTransition` for AUTO / SEEK / REPEAT
     * transitions. Synchronously checks whether the new current track's audio body is on disk;
     * if it is, this is a no-op and existing flow proceeds. If it isn't, captures the player's
     * `playWhenReady`, forces it to false, and hands off to `runCacheMissRecovery`. The
     * cache check is a single SQLite row read plus a `File.exists()` — safe on Main.
     *
     * No-op when [trackId] is null (transition to an unknown / empty queue) or when ThumpData
     * hasn't been constructed yet (onCreate is mid-flight).
     */
    private fun maybeGateCurrentTrackOnCache(player: Player, trackId: String?): Unit {
        if (trackId == null) {
            return
        }
        val thumpDataInstance: ThumpData? = serviceThumpData
        if (thumpDataInstance == null) {
            return
        }
        val cached: Boolean = thumpDataInstance.isAudioBlobCached(trackId)
        if (cached) {
            return
        }
        val capturedPlayWhenReady: Boolean = player.playWhenReady
        player.playWhenReady = false
        runCacheMissRecovery(
            player = player,
            trackId = trackId,
            playWhenReadyToRestoreOnSuccess = capturedPlayWhenReady,
        )
    }

    /**
     * Inspect a PlaybackException for our cache-miss IOException signature and, if matched,
     * pause + drive a recovery for the failing trackId. Any other PlaybackException is left
     * alone — ExoPlayer's existing error path handles those.
     *
     * The matched trackId is parsed from the IOException message (the format is stamped by
     * `ThumpData.open`); if parsing fails for any reason we fall back to the player's current
     * MediaItem id so the recovery still has something to act on.
     */
    private fun handlePlayerErrorForCacheMiss(
        player: Player,
        error: PlaybackException,
    ): Unit {
        val matchedTrackId: String? = extractCacheMissTrackId(error)
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
        val capturedPlayWhenReady: Boolean = player.playWhenReady
        player.playWhenReady = false
        runCacheMissRecovery(
            player = player,
            trackId = resolvedTrackId,
            playWhenReadyToRestoreOnSuccess = capturedPlayWhenReady,
        )
    }

    /**
     * Shared body of the gate's miss branch and the safety-net error path.
     *
     *  1. Dedupes by trackId — if a recovery is already in flight for the same id, the second
     *     caller bails out so we don't fire two prefetches and two toasts on the same miss.
     *  2. Launches a coroutine on the service scope that awaits `prefetchAudio(trackId)`.
     *  3. On success — if the player is still pointing at the same track (the user may have
     *     skipped during the await), calls `player.prepare()` and restores `playWhenReady`.
     *     If the user moved on, the new transition's own gate handles it — we no-op.
     *  4. On failure — pauses, fires a Toast, and broadcasts the unavailable reason via the
     *     MediaSession's custom-command channel so the UI process's PlaybackController can
     *     surface it on NowPlaying. ThumpDataNotConfigured / IOException / CancellationException
     *     are mapped through the same `classifyRestoreIoFailure` pattern the cold-start path
     *     uses, so error strings stay consistent across surfaces.
     */
    private fun runCacheMissRecovery(
        player: Player,
        trackId: String,
        playWhenReadyToRestoreOnSuccess: Boolean,
    ): Unit {
        val thumpDataInstance: ThumpData? = serviceThumpData
        if (thumpDataInstance == null) {
            return
        }
        val acquiredRecoverySlot: Boolean
        synchronized(inFlightCacheRecoveryLock) {
            if (inFlightCacheRecoveryTrackIds.contains(trackId)) {
                acquiredRecoverySlot = false
            } else {
                inFlightCacheRecoveryTrackIds.add(trackId)
                acquiredRecoverySlot = true
            }
        }
        if (!acquiredRecoverySlot) {
            return
        }
        serviceCoroutineScope.launch {
            var prefetchSucceeded: Boolean = false
            var prefetchFailureReason: String? = null
            try {
                thumpDataInstance.prefetchAudio(trackId)
                prefetchSucceeded = true
            } catch (cancellation: CancellationException) {
                releaseRecoverySlot(trackId)
                throw cancellation
            } catch (notConfigured: ThumpDataNotConfigured) {
                val ignoredNotConfigured: ThumpDataNotConfigured = notConfigured
                prefetchFailureReason = PlaybackController.UNAVAILABLE_REASON_NOT_CONFIGURED
            } catch (cacheMissOrTransport: IOException) {
                prefetchFailureReason = classifyRestoreIoFailure(cacheMissOrTransport)
            }
            try {
                withContext(Dispatchers.Main) {
                    val stillCurrentTrackId: String? = extractTrackId(player.currentMediaItem)
                    val stillSameTrack: Boolean = stillCurrentTrackId != null
                        && stillCurrentTrackId == trackId
                    if (prefetchSucceeded) {
                        if (stillSameTrack) {
                            player.prepare()
                            player.playWhenReady = playWhenReadyToRestoreOnSuccess
                        }
                    } else {
                        val failureMessage: String
                        if (prefetchFailureReason == null) {
                            failureMessage = PlaybackController.UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE
                        } else {
                            failureMessage = prefetchFailureReason
                        }
                        if (stillSameTrack) {
                            player.playWhenReady = false
                        }
                        Toast.makeText(
                            applicationContext,
                            failureMessage,
                            Toast.LENGTH_LONG,
                        ).show()
                        broadcastUnavailableReason(trackId, failureMessage)
                    }
                }
            } finally {
                releaseRecoverySlot(trackId)
            }
        }
    }

    private fun releaseRecoverySlot(trackId: String): Unit {
        synchronized(inFlightCacheRecoveryLock) {
            inFlightCacheRecoveryTrackIds.remove(trackId)
        }
    }

    /**
     * Cross-process push of the unavailable reason to every connected MediaController. The
     * controller in the UI process owns the NowPlaying StateFlow and surfaces the banner; this
     * is the only IPC path that carries the reason string. Toast fires service-side (see the
     * runCacheMissRecovery body) so we do not double up across processes.
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
     * Restore the persisted queue + position into the player on service startup.
     *
     * The function itself stays non-suspending — `onCreate` cannot wait — but it kicks off a
     * coroutine on the service scope that:
     *
     *  1. Awaits `thumpData.prefetchAudio(currentTrackId)` for the saved index.
     *  2. Hops to Main and calls `player.setMediaItems` + `prepare` + `playWhenReady = false`.
     *
     * On prefetch failure (offline + not cached, no protocol configured, transport error) the
     * queue is still installed via `setMediaItems` so the queue UI is restored, but `prepare`
     * is skipped. Skipping `prepare` is the whole point — `prepare` would trigger ExoPlayer's
     * first `open()` against ThumpData's cache-only DataSource and synchronously throw
     * IOException on the miss. Without `prepare` the user sees the queue restored but tapping
     * play surfaces the cache-miss through ExoPlayer's normal error path. `playWhenReady` is
     * still forced to `false` so we don't auto-play the broken track when prepare eventually
     * does run (e.g. the user comes back online and taps play).
     *
     * Reactive prefetch for indices > currentIndex continues to fire from
     * `refreshAudioPrefetchWindow` once the player listener picks up the timeline change.
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
        val currentTrackId: String = persisted.items[safeIndex].trackId
        val restorePositionMs: Long = persisted.positionMs
        val thumpDataInstance: ThumpData? = serviceThumpData
        if (thumpDataInstance == null) {
            return
        }
        serviceCoroutineScope.launch {
            var prefetchSucceeded: Boolean = false
            var prefetchFailureReason: String? = null
            try {
                thumpDataInstance.prefetchAudio(currentTrackId)
                prefetchSucceeded = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (notConfigured: ThumpDataNotConfigured) {
                // No active protocol — fall through to the queue-only restore branch.
                val ignoredNotConfigured: ThumpDataNotConfigured = notConfigured
                prefetchFailureReason = PlaybackController.UNAVAILABLE_REASON_NOT_CONFIGURED
            } catch (cacheMissOrTransport: IOException) {
                // Offline + not cached, or download failure — fall through likewise.
                prefetchFailureReason = classifyRestoreIoFailure(cacheMissOrTransport)
            }
            withContext(Dispatchers.Main) {
                if (prefetchSucceeded) {
                    player.setMediaItems(mediaItems, safeIndex, restorePositionMs)
                    player.prepare()
                    player.playWhenReady = false
                } else {
                    player.setMediaItems(mediaItems, safeIndex, restorePositionMs)
                    player.playWhenReady = false
                    val failureMessage: String
                    if (prefetchFailureReason == null) {
                        failureMessage = PlaybackController.UNAVAILABLE_REASON_GENERIC_LOAD_FAILURE
                    } else {
                        failureMessage = prefetchFailureReason
                    }
                    Toast.makeText(applicationContext, failureMessage, Toast.LENGTH_LONG).show()
                }
                currentScrobbleTrackId = currentTrackId
                hasSubmittedCurrent = false
            }
        }
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
     * inside the window get a supervised prefetch Job launched against the service scope;
     * any active Jobs for trackIds outside the window get cancelled so we don't keep paying
     * for downloads the user has skipped past.
     *
     * Each prefetch runs as an independent child of the service scope (which is backed by a
     * `SupervisorJob`) so one failed prefetch never cancels its siblings. Failures are
     * swallowed inside the Job body — the playback service is not responsible for surfacing
     * cache-miss errors; `ThumpData.open` does that synchronously when ExoPlayer reaches the
     * uncached track.
     */
    private fun refreshAudioPrefetchWindow(player: Player): Unit {
        val thumpDataInstance: ThumpData? = serviceThumpData
        if (thumpDataInstance == null) {
            return
        }
        val itemCount: Int = player.mediaItemCount
        val currentIndex: Int = player.currentMediaItemIndex
        if (itemCount <= 0 || currentIndex < 0) {
            cancelAllPrefetchJobs()
            return
        }
        val lookaheadSetting: Int = settings.getPrefetchLookahead()
        if (lookaheadSetting <= 0) {
            cancelAllPrefetchJobs()
            return
        }
        val computedEnd: Long = currentIndex.toLong() + lookaheadSetting.toLong()
        val windowEndExclusive: Int
        if (computedEnd >= itemCount.toLong()) {
            windowEndExclusive = itemCount
        } else {
            windowEndExclusive = computedEnd.toInt()
        }
        val windowTrackIds: HashSet<String> = collectWindowTrackIds(player, currentIndex, windowEndExclusive)
        cancelPrefetchJobsOutsideWindow(windowTrackIds)
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

    private fun cancelPrefetchJobsOutsideWindow(windowTrackIds: HashSet<String>): Unit {
        val staleTrackIds: ArrayList<String> = ArrayList<String>()
        synchronized(prefetchJobsLock) {
            val activeEntries: Set<Map.Entry<String, Job>> = prefetchJobsByTrackId.entries
            for (activeEntry in activeEntries) {
                val activeTrackId: String = activeEntry.key
                if (!windowTrackIds.contains(activeTrackId)) {
                    staleTrackIds.add(activeTrackId)
                }
            }
        }
        val staleCount: Int = staleTrackIds.size
        for (staleIndex in 0 until staleCount) {
            val staleTrackId: String = staleTrackIds[staleIndex]
            val jobToCancel: Job?
            synchronized(prefetchJobsLock) {
                jobToCancel = prefetchJobsByTrackId.remove(staleTrackId)
            }
            if (jobToCancel != null) {
                jobToCancel.cancel()
            }
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
        synchronized(prefetchJobsLock) {
            if (prefetchJobsByTrackId.containsKey(trackId)) {
                return
            }
        }
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

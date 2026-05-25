package com.therobm.thump.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 */
class ThumpPlaybackService : MediaLibraryService() {

    private var librarySession: MediaLibrarySession? = null
    private val serviceCoroutineScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    private val credentialsLoader: PlaybackCredentialsLoader by lazy {
        PlaybackCredentialsLoader(applicationContext)
    }
    private val persistence: PlaybackPersistence by lazy {
        PlaybackPersistence(applicationContext)
    }

    // Scrobble state for the currently-playing track. Reset on every media-item transition.
    private var currentScrobbleTrackId: String? = null
    private var hasSubmittedCurrent: Boolean = false

    private var prefetcher: AudioPrefetcher? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val cacheFactory = AudioCacheFactory(applicationContext)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(cacheFactory.buildMediaSourceFactory())
            .build()

        prefetcher = AudioPrefetcher(
            cacheDataSourceFactory = cacheFactory.buildCacheDataSourceFactory(),
            scope = serviceCoroutineScope,
        )

        player.addListener(buildPlayerListener(player))

        val callback = ThumpMediaLibraryCallback(
            applicationCoroutineScope = serviceCoroutineScope,
            credentialsLoader = credentialsLoader,
            applicationPackageName = applicationContext.packageName,
        )
        librarySession = MediaLibrarySession.Builder(this, player, callback).build()

        restorePersistedStateInto(player)
        // After restore the player carries the resumed queue; kick off lookahead so the user's
        // next-track when they resume is already on disk.
        triggerPrefetch(player)
        startPositionPersistenceLoop(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return librarySession
    }

    override fun onDestroy() {
        val activePrefetcher = prefetcher
        if (activePrefetcher != null) {
            activePrefetcher.cancelInFlight()
            prefetcher = null
        }
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
                val trackId = extractTrackId(mediaItem)
                currentScrobbleTrackId = trackId
                hasSubmittedCurrent = false
                if (trackId != null) {
                    fireScrobbleNowPlaying(trackId)
                }
                persistCurrentPlayerState(player)
                triggerPrefetch(player)
            }
        }
    }

    /**
     * Collect the post-current stream URLs from the player on the main thread and hand them to
     * the prefetcher, which downloads them on IO. Called from onCreate (after restore) and from
     * the Player.Listener (both already on main).
     */
    private fun triggerPrefetch(player: Player) {
        val itemCount = player.mediaItemCount
        if (itemCount <= 0) {
            return
        }
        val streamUrls = ArrayList<String>(itemCount)
        for (itemIndex in 0 until itemCount) {
            val mediaItemAtIndex = player.getMediaItemAt(itemIndex)
            val localConfig = mediaItemAtIndex.localConfiguration
            if (localConfig == null) {
                streamUrls.add("")
            } else {
                streamUrls.add(localConfig.uri.toString())
            }
        }
        val currentIndex = player.currentMediaItemIndex
        val activePrefetcher = prefetcher
        if (activePrefetcher == null) {
            return
        }
        activePrefetcher.startPrefetch(streamUrls, currentIndex)
    }

    private fun restorePersistedStateInto(player: Player) {
        val persisted = persistence.load()
        if (persisted == null) {
            return
        }
        if (persisted.items.isEmpty()) {
            return
        }
        val mediaItems = ArrayList<MediaItem>(persisted.items.size)
        val itemCount = persisted.items.size
        for (itemIndex in 0 until itemCount) {
            val item = persisted.items[itemIndex]
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
        player.setMediaItems(mediaItems, safeIndex, persisted.positionMs)
        player.prepare()
        player.playWhenReady = false
        currentScrobbleTrackId = persisted.items[safeIndex].trackId
        hasSubmittedCurrent = false
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
        val coverArtUrl = item.coverArtUrl
        if (coverArtUrl != null) {
            metadataBuilder.setArtworkUri(android.net.Uri.parse(coverArtUrl))
        }
        return MediaItem.Builder()
            .setMediaId("thump-track/" + item.trackId)
            .setUri(item.streamUrl)
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
        serviceCoroutineScope.launch {
            val client = credentialsLoader.loadSubsonicClient()
            if (client == null) {
                return@launch
            }
            client.scrobble(trackId, submission = false)
        }
    }

    private fun fireScrobbleSubmission(trackId: String) {
        serviceCoroutineScope.launch {
            val client = credentialsLoader.loadSubsonicClient()
            if (client == null) {
                return@launch
            }
            client.scrobble(trackId, submission = true)
        }
    }

    private fun persistCurrentPlayerState(player: Player) {
        val itemCount = player.mediaItemCount
        if (itemCount <= 0) {
            persistence.clear()
            return
        }
        val previous = persistence.load()
        // We need title/artist/album/streamUrl/coverArtUrl to round-trip. The player only knows
        // titles via MediaMetadata; streamUrl and coverArtUrl are held in the previously
        // persisted blob (or were just written by the app's PlaybackController on playQueue).
        // Fall back to whatever's there; the player's setMediaItems was driven by that blob in
        // the first place when the service restored.
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

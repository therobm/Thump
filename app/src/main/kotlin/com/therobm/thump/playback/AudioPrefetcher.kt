package com.therobm.thump.playback

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Caches the next N tracks in the queue ahead of the playhead so they're already on disk
 * when the player advances. Uses Media3's CacheWriter, which reads through the same
 * CacheDataSource pipeline ExoPlayer uses — bytes end up keyed by trackId in the shared
 * SimpleCache, so a tap-to-play in the player after prefetch hits disk instead of network.
 *
 * Cancels any in-flight prefetch on every call so a queue change or skip doesn't waste
 * bandwidth on now-stale items.
 */
class AudioPrefetcher(
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val scope: CoroutineScope,
) {

    private var currentJob: Job? = null
    @Volatile
    private var activeWriter: CacheWriter? = null

    /**
     * Kick off a prefetch of up to PREFETCH_LOOKAHEAD items after `currentIndex` in the
     * queue. Each track is downloaded sequentially; a track that is already fully cached
     * resolves almost instantly (CacheWriter detects the cached span and skips upstream).
     */
    fun startPrefetch(streamUrls: List<String>, currentIndex: Int) {
        cancelInFlight()
        if (streamUrls.isEmpty()) {
            return
        }
        val startIndex = currentIndex + 1
        if (startIndex >= streamUrls.size) {
            return
        }
        val endIndex: Int
        val proposedEnd = startIndex + PREFETCH_LOOKAHEAD
        if (proposedEnd > streamUrls.size) {
            endIndex = streamUrls.size
        } else {
            endIndex = proposedEnd
        }

        currentJob = scope.launch(Dispatchers.IO) {
            for (i in startIndex until endIndex) {
                if (!isActive) {
                    break
                }
                val streamUrl = streamUrls[i]
                val dataSpec = DataSpec.Builder()
                    .setUri(streamUrl)
                    .build()
                val rawDataSource = cacheDataSourceFactory.createDataSource()
                if (rawDataSource !is CacheDataSource) {
                    continue
                }
                val writer = CacheWriter(rawDataSource, dataSpec, null, null)
                activeWriter = writer
                try {
                    writer.cache()
                } catch (prefetchFailure: Exception) {
                    // Best-effort — connection failed, server hiccup, etc. The track will fall
                    // back to streaming when the user actually plays it.
                } finally {
                    activeWriter = null
                }
            }
        }
    }

    fun cancelInFlight() {
        val job = currentJob
        if (job != null) {
            job.cancel()
            currentJob = null
        }
        val writer = activeWriter
        if (writer != null) {
            writer.cancel()
            activeWriter = null
        }
    }

    companion object {
        // Spec default — fetch the next 10 tracks while the current one plays. Plenty of head
        // start for the "tunnel mid-playlist" case the spec calls out. Tunable later once the
        // Settings screen exposes it.
        private const val PREFETCH_LOOKAHEAD: Int = 10
    }
}

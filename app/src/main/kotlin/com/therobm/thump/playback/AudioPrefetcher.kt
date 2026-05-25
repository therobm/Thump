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
     * Kick off a prefetch of up to `lookahead` items after `currentIndex` in the queue. Each
     * track is downloaded sequentially; a track that is already fully cached resolves almost
     * instantly (CacheWriter detects the cached span and skips upstream). `lookahead` of 0 is
     * a valid "prefetch disabled" value — the call becomes a no-op after cancelling any
     * in-flight work.
     */
    fun startPrefetch(streamUrls: List<String>, currentIndex: Int, lookahead: Int) {
        cancelInFlight()
        if (lookahead <= 0) {
            return
        }
        if (streamUrls.isEmpty()) {
            return
        }
        val startIndex: Int = currentIndex + 1
        if (startIndex >= streamUrls.size) {
            return
        }
        val endIndex: Int
        val proposedEnd: Int = startIndex + lookahead
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
                val streamUrl: String = streamUrls[i]
                val dataSpec: DataSpec = DataSpec.Builder()
                    .setUri(streamUrl)
                    .build()
                val rawDataSource: androidx.media3.datasource.DataSource = cacheDataSourceFactory.createDataSource()
                if (rawDataSource !is CacheDataSource) {
                    continue
                }
                val writer: CacheWriter = CacheWriter(rawDataSource, dataSpec, null, null)
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
        val job: Job? = currentJob
        if (job != null) {
            job.cancel()
            currentJob = null
        }
        val writer: CacheWriter? = activeWriter
        if (writer != null) {
            writer.cancel()
            activeWriter = null
        }
    }
}

package com.therobm.thump.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.therobm.thump.settings.ThumpSettings
import java.io.File

/**
 * Owns the process-wide audio cache and builds the MediaSource.Factory that wires it into
 * ExoPlayer.
 *
 * Write-through: as ExoPlayer streams /rest/stream, bytes flow through CacheDataSource which
 * also writes them to disk under filesDir/audio_cache, keyed by trackId. Subsequent plays of
 * the same trackId read from disk with no network.
 *
 * Eviction: LeastRecentlyUsedCacheEvictor caps the cache at AUDIO_CACHE_MAX_BYTES (500 MB per
 * the spec default). Last-accessed metadata is maintained by SimpleCache itself.
 *
 * Cache key: Subsonic's stream URL embeds a per-request salt + token, so the URL changes
 * across sessions even for the same track. We override the cache key to the stable trackId
 * (extracted from the URL's `id` query parameter) so the cache survives credential rotation,
 * server URL rewrites, and any other change that doesn't actually affect the bytes.
 *
 * Single-instance: SimpleCache requires at most one instance per directory per process, so the
 * cache is held in a companion-object singleton with thread-safe initialization.
 */
class AudioCacheFactory(private val applicationContext: Context) {

    fun buildMediaSourceFactory(): MediaSource.Factory {
        return DefaultMediaSourceFactory(applicationContext)
            .setDataSourceFactory(buildCacheDataSourceFactory())
    }

    /**
     * Returns a CacheDataSource.Factory wrapping the shared SimpleCache. ExoPlayer uses one
     * to play with write-through; the prefetcher uses its own to download tracks ahead of the
     * playhead. Both end up writing to the same disk cache because they reference the same
     * SimpleCache singleton.
     */
    fun buildCacheDataSourceFactory(): CacheDataSource.Factory {
        val cache = obtainCache(applicationContext)
        val upstreamFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory { dataSpec ->
                val trackId = dataSpec.uri.getQueryParameter("id")
                if (trackId == null) {
                    dataSpec.uri.toString()
                } else {
                    "track-" + trackId
                }
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    companion object {
        private const val AUDIO_CACHE_DIRECTORY_NAME: String = "audio_cache"
        private const val HTTP_CONNECT_TIMEOUT_MS: Int = 15_000
        private const val HTTP_READ_TIMEOUT_MS: Int = 20_000

        private var sharedCache: SimpleCache? = null
        private val initLock: Any = Any()

        fun obtainCache(applicationContext: Context): SimpleCache {
            val existing: SimpleCache? = sharedCache
            if (existing != null) {
                return existing
            }
            synchronized(initLock) {
                val recheck: SimpleCache? = sharedCache
                if (recheck != null) {
                    return recheck
                }
                val cacheDirectory: File = File(applicationContext.filesDir, AUDIO_CACHE_DIRECTORY_NAME)
                if (!cacheDirectory.exists()) {
                    cacheDirectory.mkdirs()
                }
                // Cache size is read once per process lifetime. Changing it from Settings
                // requires an app restart; the Settings UI calls this out next to the slider.
                val settings: ThumpSettings = ThumpSettings(applicationContext)
                val capBytes: Long = settings.getAudioCacheSizeBytes()
                val evictor: LeastRecentlyUsedCacheEvictor = LeastRecentlyUsedCacheEvictor(capBytes)
                val databaseProvider: StandaloneDatabaseProvider = StandaloneDatabaseProvider(applicationContext)
                val newCache: SimpleCache = SimpleCache(cacheDirectory, evictor, databaseProvider)
                sharedCache = newCache
                return newCache
            }
        }

        /**
         * Empty the entire audio cache. Safe to call while playback is happening — in-flight
         * reads finish using cached bytes already handed out; subsequent reads of evicted
         * tracks fall through to the upstream HTTP factory.
         */
        fun clearCache(applicationContext: Context) {
            val cache: SimpleCache = obtainCache(applicationContext)
            val keys: Set<String> = cache.keys.toSet()
            for (key in keys) {
                cache.removeResource(key)
            }
        }
    }
}

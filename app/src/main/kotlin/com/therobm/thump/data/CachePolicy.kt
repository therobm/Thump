package com.therobm.thump.data

/**
 * Per-call cache policy. Defaults differ by call kind (metadata vs blob); callers can override.
 *
 * - NetworkFirst: fetch fresh; serve cache only on network failure. Default for metadata.
 * - CacheFirst: serve cache if present; fetch on miss. Default for binary blobs.
 * - NetworkOnly: skip cache, never store. Default for mutations.
 * - CacheOnly: serve cache or fail. Used by explicit offline mode.
 */
enum class CachePolicy {
    NetworkFirst,
    CacheFirst,
    NetworkOnly,
    CacheOnly,
}

package com.therobm.thump.data

/**
 * Snapshot of the blob store's on-disk footprint. Returned by
 * `ThumpData.getBlobStoreStats` / `ThumpBlobStore.getStats`. A point-in-time read;
 * the Settings panel re-fetches on screen entry and on a manual refresh tap.
 *
 * `oldestAccessedAtEpochMillis` is 0L when the index is empty.
 */
data class BlobStoreStats(
    val totalUsedBytes: Long,
    val audioBlobCount: Int,
    val coverArtBlobCount: Int,
    val oldestAccessedAtEpochMillis: Long,
)

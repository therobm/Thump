package com.therobm.thump.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * On-disk blob directory plus the matching `blobs` row in ThumpData's SQLite database. One
 * file per blob; the file path is recorded only after the file is renamed into place. Reads
 * touch the SQLite row's `last_accessed_at_epoch_millis` for the LRU eviction sweep that runs
 * after every write.
 *
 * Blob keys follow a small vocabulary so the index can be filtered cheaply:
 *
 * - `coverArt:<id>:<sizePx>` — a cover-art rendering at a specific size
 * - `track:<id>` — a track's full audio body
 *
 * The atomic write goes: write `<final>.tmp`, fsync (via FileOutputStream.close), rename to
 * `<final>`, then INSERT/REPLACE the SQLite row in a transaction that also runs the LRU
 * eviction. A torn write leaves a `.tmp` behind that the next eviction sweep can clean; no
 * incomplete blob is ever observable through the index.
 *
 * Eviction is synchronous-on-write per the architecture spec: after the inserted row lands,
 * the transaction sums `size_bytes` across all rows; if the total exceeds the configured cap,
 * it deletes the row with the oldest `last_accessed_at_epoch_millis` (and its on-disk file)
 * and repeats until the total is back under the cap. Holding the SQLite transaction over the
 * sweep is what stops the UI process and the MediaLibraryService process from racing on which
 * blob is next to go.
 */
class ThumpBlobStore(
    private val database: ThumpDatabase,
    applicationContext: Context,
    private val cacheSizeBytesProvider: () -> Long,
) {

    private val blobDirectory: File = File(applicationContext.filesDir, BLOB_DIRECTORY_NAME)

    init {
        if (!blobDirectory.exists()) {
            val created: Boolean = blobDirectory.mkdirs()
            if (!created && !blobDirectory.exists()) {
                throw IllegalStateException(
                    "ThumpBlobStore could not create blob directory: " + blobDirectory.absolutePath
                )
            }
        }
    }

    /**
     * Look up a blob by key. Returns null when the row is missing or when the row references
     * a file that has been removed out from under us (a torn delete on an older version, say).
     * Reads bump the `last_accessed_at_epoch_millis` column so the LRU sweep treats this as a
     * fresh touch.
     */
    fun readBlobBytes(blobKey: String): ByteArray? {
        val writableDatabase: SQLiteDatabase = database.writableDatabase
        val cursor: Cursor = writableDatabase.rawQuery(
            "SELECT file_path FROM blobs WHERE blob_key = ?",
            arrayOf<String>(blobKey),
        )
        val filePath: String?
        try {
            if (!cursor.moveToFirst()) {
                return null
            }
            filePath = cursor.getString(0)
        } finally {
            cursor.close()
        }
        if (filePath == null) {
            return null
        }
        val blobFile: File = File(filePath)
        if (!blobFile.exists()) {
            // The index row points at a file that has been deleted; remove the stale row so
            // future calls miss cleanly instead of looping back here.
            writableDatabase.delete(
                "blobs",
                "blob_key = ?",
                arrayOf<String>(blobKey),
            )
            return null
        }
        val bytes: ByteArray = readAllBytes(blobFile)
        val now: Long = System.currentTimeMillis()
        val touchedRow: ContentValues = ContentValues()
        touchedRow.put("last_accessed_at_epoch_millis", now)
        writableDatabase.update(
            "blobs",
            touchedRow,
            "blob_key = ?",
            arrayOf<String>(blobKey),
        )
        return bytes
    }

    /**
     * Persist bytes for a blob. The bytes are written to `<dir>/<random>.tmp`, then renamed to
     * the final path, then indexed in a SQLite transaction that also runs the LRU eviction
     * sweep. A failed rename surfaces as an exception; a failed insert after a successful
     * rename leaves the file on disk to be cleaned by a future eviction.
     */
    fun writeBlobBytes(
        blobKey: String,
        bytes: ByteArray,
        contentType: String?,
    ): Unit {
        val finalFile: File = File(blobDirectory, deriveFileName(blobKey))
        val temporaryFile: File = File(blobDirectory, finalFile.name + "." + UUID.randomUUID().toString() + ".tmp")
        val outputStream: FileOutputStream = FileOutputStream(temporaryFile)
        try {
            outputStream.write(bytes)
            outputStream.flush()
            outputStream.fd.sync()
        } finally {
            outputStream.close()
        }
        if (finalFile.exists()) {
            val deleted: Boolean = finalFile.delete()
            if (!deleted) {
                throw IllegalStateException(
                    "ThumpBlobStore could not replace existing blob file: " + finalFile.absolutePath
                )
            }
        }
        val renamed: Boolean = temporaryFile.renameTo(finalFile)
        if (!renamed) {
            throw IllegalStateException(
                "ThumpBlobStore atomic rename failed: " + temporaryFile.absolutePath
                    + " -> " + finalFile.absolutePath
            )
        }
        val writableDatabase: SQLiteDatabase = database.writableDatabase
        writableDatabase.beginTransaction()
        try {
            val now: Long = System.currentTimeMillis()
            val row: ContentValues = ContentValues()
            row.put("blob_key", blobKey)
            row.put("file_path", finalFile.absolutePath)
            row.put("size_bytes", bytes.size.toLong())
            row.put("content_type", contentType)
            row.put("fetched_at_epoch_millis", now)
            row.put("last_accessed_at_epoch_millis", now)
            writableDatabase.insertWithOnConflict(
                "blobs",
                null,
                row,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            enforceLruCap(writableDatabase)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * Look up an existing blob's File without reading its bytes. Returns null when the row
     * is missing or its file has been removed out from under us; in the latter case the stale
     * row is dropped so future lookups miss cleanly. Bumps `last_accessed_at_epoch_millis` so
     * the LRU sweep treats this as a fresh touch — the audio path uses this on every
     * DataSource.open call.
     */
    fun openBlobFile(blobKey: String): File? {
        val writableDatabase: SQLiteDatabase = database.writableDatabase
        val cursor: Cursor = writableDatabase.rawQuery(
            "SELECT file_path FROM blobs WHERE blob_key = ?",
            arrayOf<String>(blobKey),
        )
        val filePath: String?
        try {
            if (!cursor.moveToFirst()) {
                return null
            }
            filePath = cursor.getString(0)
        } finally {
            cursor.close()
        }
        if (filePath == null) {
            return null
        }
        val blobFile: File = File(filePath)
        if (!blobFile.exists()) {
            writableDatabase.delete(
                "blobs",
                "blob_key = ?",
                arrayOf<String>(blobKey),
            )
            return null
        }
        val touchedRow: ContentValues = ContentValues()
        touchedRow.put("last_accessed_at_epoch_millis", System.currentTimeMillis())
        writableDatabase.update(
            "blobs",
            touchedRow,
            "blob_key = ?",
            arrayOf<String>(blobKey),
        )
        return blobFile
    }

    /**
     * Reserve a temporary file path inside the blob directory for a streamed write. The
     * returned handle exposes the temporary File for the caller to write into; on success the
     * caller hands it to `commitTemporaryBlobFile` for the atomic rename + index insert. On
     * failure the caller must call `discardTemporaryBlobFile` to remove the partial write.
     *
     * Used by the audio prefetch path so large bodies stream straight to disk instead of going
     * through a `ByteArray`.
     */
    fun createTemporaryBlobFile(blobKey: String): TemporaryBlobHandle {
        val finalFile: File = File(blobDirectory, deriveFileName(blobKey))
        val temporaryFile: File = File(
            blobDirectory,
            finalFile.name + "." + UUID.randomUUID().toString() + ".tmp",
        )
        return TemporaryBlobHandle(
            blobKey = blobKey,
            temporaryFile = temporaryFile,
            finalFile = finalFile,
        )
    }

    /**
     * Atomically promote a temporary blob file into the on-disk store and index it in SQLite.
     * Mirrors the in-memory `writeBlobBytes` write path: replace any existing final file,
     * rename the temp file into place, then insert/replace the row and run the LRU sweep
     * inside one SQLite transaction.
     */
    fun commitTemporaryBlobFile(
        handle: TemporaryBlobHandle,
        sizeBytes: Long,
        contentType: String?,
    ): Unit {
        if (handle.finalFile.exists()) {
            val deleted: Boolean = handle.finalFile.delete()
            if (!deleted) {
                throw IllegalStateException(
                    "ThumpBlobStore could not replace existing blob file: " + handle.finalFile.absolutePath
                )
            }
        }
        val renamed: Boolean = handle.temporaryFile.renameTo(handle.finalFile)
        if (!renamed) {
            throw IllegalStateException(
                "ThumpBlobStore atomic rename failed: " + handle.temporaryFile.absolutePath
                    + " -> " + handle.finalFile.absolutePath
            )
        }
        val writableDatabase: SQLiteDatabase = database.writableDatabase
        writableDatabase.beginTransaction()
        try {
            val now: Long = System.currentTimeMillis()
            val row: ContentValues = ContentValues()
            row.put("blob_key", handle.blobKey)
            row.put("file_path", handle.finalFile.absolutePath)
            row.put("size_bytes", sizeBytes)
            row.put("content_type", contentType)
            row.put("fetched_at_epoch_millis", now)
            row.put("last_accessed_at_epoch_millis", now)
            writableDatabase.insertWithOnConflict(
                "blobs",
                null,
                row,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            enforceLruCap(writableDatabase)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * Remove a temporary file produced by `createTemporaryBlobFile` when its write failed or
     * was cancelled. Idempotent — a missing file is not an error.
     */
    fun discardTemporaryBlobFile(handle: TemporaryBlobHandle): Unit {
        if (handle.temporaryFile.exists()) {
            handle.temporaryFile.delete()
        }
    }

    /**
     * Delete a single blob (file + index row). Idempotent — missing files and rows are not
     * errors. Used by `invalidate` and by the eviction sweep when it lands.
     */
    fun deleteBlob(blobKey: String): Unit {
        val writableDatabase: SQLiteDatabase = database.writableDatabase
        val cursor: Cursor = writableDatabase.rawQuery(
            "SELECT file_path FROM blobs WHERE blob_key = ?",
            arrayOf<String>(blobKey),
        )
        val filePath: String?
        try {
            if (!cursor.moveToFirst()) {
                filePath = null
            } else {
                filePath = cursor.getString(0)
            }
        } finally {
            cursor.close()
        }
        if (filePath != null) {
            val blobFile: File = File(filePath)
            if (blobFile.exists()) {
                blobFile.delete()
            }
        }
        writableDatabase.delete(
            "blobs",
            "blob_key = ?",
            arrayOf<String>(blobKey),
        )
    }

    /**
     * Read-only stats over the `blobs` table for the Settings cache panel. Uses three separate
     * aggregate queries (rather than one with `FILTER (WHERE ...)`) so this stays compatible
     * with the SQLite version shipped by older Android API levels. The numbers are a point-in-
     * time snapshot; the panel re-fetches on entry and on a manual refresh.
     */
    fun getStats(): BlobStoreStats {
        val readableDatabase: SQLiteDatabase = database.readableDatabase
        val totalUsedBytes: Long = readTotalBlobBytes(readableDatabase)
        val audioBlobCount: Int = readBlobCountWithKeyPrefix(readableDatabase, "track:%")
        val coverArtBlobCount: Int = readBlobCountWithKeyPrefix(readableDatabase, "coverArt:%")
        val oldestAccessedAtEpochMillis: Long = readOldestAccessedAtEpochMillis(readableDatabase)
        return BlobStoreStats(
            totalUsedBytes = totalUsedBytes,
            audioBlobCount = audioBlobCount,
            coverArtBlobCount = coverArtBlobCount,
            oldestAccessedAtEpochMillis = oldestAccessedAtEpochMillis,
        )
    }

    private fun readBlobCountWithKeyPrefix(readableDatabase: SQLiteDatabase, likePattern: String): Int {
        val cursor: Cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM blobs WHERE blob_key LIKE ?",
            arrayOf<String>(likePattern),
        )
        try {
            if (!cursor.moveToFirst()) {
                return 0
            }
            return cursor.getInt(0)
        } finally {
            cursor.close()
        }
    }

    private fun readOldestAccessedAtEpochMillis(readableDatabase: SQLiteDatabase): Long {
        val cursor: Cursor = readableDatabase.rawQuery(
            "SELECT IFNULL(MIN(last_accessed_at_epoch_millis), 0) FROM blobs",
            arrayOf<String>(),
        )
        try {
            if (!cursor.moveToFirst()) {
                return 0L
            }
            return cursor.getLong(0)
        } finally {
            cursor.close()
        }
    }

    private fun enforceLruCap(writableDatabase: SQLiteDatabase): Unit {
        val capBytes: Long = cacheSizeBytesProvider()
        val totalBytesAtStart: Long = readTotalBlobBytes(writableDatabase)
        if (totalBytesAtStart <= capBytes) {
            return
        }
        val rowCount: Int = readBlobRowCount(writableDatabase)
        var runningTotalBytes: Long = totalBytesAtStart
        // Bounded by the current row count — at most one eviction per row before we either
        // drop under cap or empty the index. No risk of an unbounded loop.
        for (evictionStep in 0 until rowCount) {
            if (runningTotalBytes <= capBytes) {
                return
            }
            val evictedSize: Long = evictOldestBlob(writableDatabase)
            if (evictedSize < 0L) {
                return
            }
            runningTotalBytes -= evictedSize
        }
    }

    private fun readTotalBlobBytes(writableDatabase: SQLiteDatabase): Long {
        val cursor: Cursor = writableDatabase.rawQuery(
            "SELECT IFNULL(SUM(size_bytes), 0) FROM blobs",
            arrayOf<String>(),
        )
        try {
            if (!cursor.moveToFirst()) {
                return 0L
            }
            return cursor.getLong(0)
        } finally {
            cursor.close()
        }
    }

    private fun readBlobRowCount(writableDatabase: SQLiteDatabase): Int {
        val cursor: Cursor = writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM blobs",
            arrayOf<String>(),
        )
        try {
            if (!cursor.moveToFirst()) {
                return 0
            }
            return cursor.getInt(0)
        } finally {
            cursor.close()
        }
    }

    /**
     * Delete the row with the oldest `last_accessed_at_epoch_millis` and its on-disk file.
     * Returns the byte count of the evicted blob so the caller can decrement the running total,
     * or `-1L` when there is nothing left to evict.
     */
    private fun evictOldestBlob(writableDatabase: SQLiteDatabase): Long {
        val cursor: Cursor = writableDatabase.rawQuery(
            "SELECT blob_key, file_path, size_bytes FROM blobs "
                + "ORDER BY last_accessed_at_epoch_millis ASC LIMIT 1",
            arrayOf<String>(),
        )
        val evictKey: String
        val evictPath: String?
        val evictSize: Long
        try {
            if (!cursor.moveToFirst()) {
                return -1L
            }
            evictKey = cursor.getString(0)
            if (cursor.isNull(1)) {
                evictPath = null
            } else {
                evictPath = cursor.getString(1)
            }
            evictSize = cursor.getLong(2)
        } finally {
            cursor.close()
        }
        if (evictPath != null) {
            val evictFile: File = File(evictPath)
            if (evictFile.exists()) {
                evictFile.delete()
            }
        }
        writableDatabase.delete(
            "blobs",
            "blob_key = ?",
            arrayOf<String>(evictKey),
        )
        return evictSize
    }

    private fun deriveFileName(blobKey: String): String {
        // Don't expose the raw key in the filename (it can contain colons that break on some
        // filesystems and would leak metadata). Hash it instead; the SQLite row holds the real
        // mapping. SHA-256 is overkill for collision-resistance here but it's already on the
        // platform and the hash is computed once per write.
        val digest: ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(blobKey.toByteArray(Charsets.UTF_8))
        return bytesToLowerHex(digest)
    }

    private fun bytesToLowerHex(source: ByteArray): String {
        val hexCharacters: String = "0123456789abcdef"
        val sourceLength: Int = source.size
        val result: StringBuilder = StringBuilder(sourceLength * 2)
        for (sourceByteIndex in 0 until sourceLength) {
            val currentByte: Int = source[sourceByteIndex].toInt() and 0xff
            result.append(hexCharacters[currentByte ushr 4])
            result.append(hexCharacters[currentByte and 0x0f])
        }
        return result.toString()
    }

    private fun readAllBytes(source: File): ByteArray {
        val totalLength: Long = source.length()
        if (totalLength > Int.MAX_VALUE.toLong()) {
            throw IllegalStateException(
                "ThumpBlobStore blob too large to read into memory: " + source.absolutePath
            )
        }
        val buffer: ByteArray = ByteArray(totalLength.toInt())
        val inputStream: FileInputStream = FileInputStream(source)
        try {
            var bytesReadSoFar: Int = 0
            // Bounded by buffer.size — InputStream.read may return a short read, so we loop
            // until we've filled the buffer or hit EOF. The upper bound is the buffer size
            // (one-byte-per-call worst case).
            for (readAttemptIndex in 0 until buffer.size) {
                if (bytesReadSoFar >= buffer.size) {
                    break
                }
                val remainingBytes: Int = buffer.size - bytesReadSoFar
                val readThisCall: Int = inputStream.read(buffer, bytesReadSoFar, remainingBytes)
                if (readThisCall < 0) {
                    break
                }
                bytesReadSoFar += readThisCall
            }
        } finally {
            inputStream.close()
        }
        return buffer
    }

    companion object {
        const val BLOB_DIRECTORY_NAME: String = "thump_blobs"

        /**
         * Build the canonical blob key for a cover-art rendering. Keeping the format in one
         * place avoids divergence between cache reads and cache writes.
         */
        fun coverArtBlobKey(coverArtId: String, sizePx: Int): String {
            return "coverArt:" + coverArtId + ":" + sizePx.toString()
        }

        /**
         * Build the canonical blob key for a track's audio body.
         */
        fun trackBlobKey(trackId: String): String {
            return "track:" + trackId
        }
    }

    /**
     * Handle returned by `createTemporaryBlobFile`. The caller writes into `temporaryFile`,
     * then hands the handle back to `commitTemporaryBlobFile` for the atomic rename + index
     * insert (or to `discardTemporaryBlobFile` to clean up on failure).
     */
    class TemporaryBlobHandle internal constructor(
        internal val blobKey: String,
        val temporaryFile: File,
        internal val finalFile: File,
    )
}

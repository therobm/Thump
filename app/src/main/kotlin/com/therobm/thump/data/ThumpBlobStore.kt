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
 * touch the SQLite row's `last_accessed_at_epoch_millis` for the LRU sweep that lands in a
 * later step.
 *
 * Blob keys follow a small vocabulary so the index can be filtered cheaply:
 *
 * - `coverArt:<id>:<sizePx>` — a cover-art rendering at a specific size
 * - `track:<id>` — a track's full audio body
 *
 * The atomic write goes: write `<final>.tmp`, fsync (via FileOutputStream.close), rename to
 * `<final>`, then INSERT/REPLACE the SQLite row. A torn write leaves a `.tmp` behind that the
 * eviction sweep can clean; no incomplete blob is ever observable through the index.
 *
 * Step 2 status: paths are wired through but no caller exercises CacheFirst yet. ThumpData
 * defaults its binary fetches to NetworkOnly through the IProtocol for the skeleton step;
 * the disk-hit-or-fetch policy lands when the Home screen port (step 3) starts using cover
 * art at volume.
 */
class ThumpBlobStore(
    private val database: ThumpDatabase,
    applicationContext: Context,
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
     * the final path, then indexed. A failed rename surfaces as an exception; a failed insert
     * after a successful rename leaves the file on disk to be cleaned by a future eviction.
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
        val now: Long = System.currentTimeMillis()
        val row: ContentValues = ContentValues()
        row.put("blob_key", blobKey)
        row.put("file_path", finalFile.absolutePath)
        row.put("size_bytes", bytes.size.toLong())
        row.put("content_type", contentType)
        row.put("fetched_at_epoch_millis", now)
        row.put("last_accessed_at_epoch_millis", now)
        database.writableDatabase.insertWithOnConflict(
            "blobs",
            null,
            row,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
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
}

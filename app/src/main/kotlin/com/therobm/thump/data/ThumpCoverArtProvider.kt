package com.therobm.thump.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.therobm.thump.ThumpApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileOutputStream
import java.io.IOException

/**
 * Cross-process bridge for cover-art bytes. Android Auto's media browser resolves
 * `content://com.therobm.thump.coverart/<artId>?size=<px>` URIs in its own process and cannot
 * suspend into our code; the ContentProvider runs inside the service process and turns the
 * URI into a `ParcelFileDescriptor` whose other end is fed by ThumpData.
 *
 * Lives in the MediaLibraryService process per the manifest declaration. In-app cover art
 * never goes through this provider — composables call `ThumpData.getCoverArt` directly.
 *
 * Per the spec for `ContentProvider.openFile`: the returned PFD has its read end exposed to
 * the caller; we write to the write end on a background coroutine and close it when done. A
 * broken pipe (Auto disconnects before reading) surfaces as an `IOException` inside the
 * coroutine and is swallowed.
 */
class ThumpCoverArtProvider : ContentProvider() {

    private val backgroundScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )

    override fun onCreate(): Boolean {
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val artId: String? = parseArtIdFromUri(uri)
        if (artId == null) {
            throw IllegalArgumentException(
                "ThumpCoverArtProvider expected /<artId> in URI; got " + uri.toString()
            )
        }
        val sizePx: Int = parseSizeFromUri(uri)
        val thumpData: ThumpData = resolveThumpDataOrThrow()
        val pipePair: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()
        val readEnd: ParcelFileDescriptor = pipePair[0]
        val writeEnd: ParcelFileDescriptor = pipePair[1]
        backgroundScope.launch {
            writeCoverArtIntoPipe(
                artId = artId,
                sizePx = sizePx,
                writeEnd = writeEnd,
                thumpData = thumpData,
            )
        }
        return readEnd
    }

    override fun getType(uri: Uri): String? {
        return "image/*"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        // ContentProvider.query is required by the abstract class; Android Auto only calls
        // openFile on us, so returning null keeps the contract without inventing a fake row.
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        return 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    private suspend fun writeCoverArtIntoPipe(
        artId: String,
        sizePx: Int,
        writeEnd: ParcelFileDescriptor,
        thumpData: ThumpData,
    ): Unit {
        val outputStream: FileOutputStream = FileOutputStream(writeEnd.fileDescriptor)
        try {
            val bytes: ByteArray = thumpData.getCoverArtBytes(artId, sizePx)
            outputStream.write(bytes)
            outputStream.flush()
        } catch (writeFailure: IOException) {
            // Broken pipe is the normal failure mode (consumer disconnected). The PFD will be
            // closed in finally; nothing further to do here.
        } finally {
            try {
                outputStream.close()
            } catch (closeFailure: IOException) {
                // Already closing the pipe — swallow.
            }
            try {
                writeEnd.close()
            } catch (pfdCloseFailure: IOException) {
                // Best-effort PFD close. Nothing to do.
            }
        }
    }

    private fun parseArtIdFromUri(uri: Uri): String? {
        val pathSegments: List<String> = uri.pathSegments
        if (pathSegments.isEmpty()) {
            return null
        }
        return pathSegments[0]
    }

    private fun parseSizeFromUri(uri: Uri): Int {
        val sizeParam: String? = uri.getQueryParameter("size")
        if (sizeParam == null) {
            return DEFAULT_COVER_ART_SIZE_PX
        }
        val asInt: Int
        try {
            asInt = sizeParam.toInt()
        } catch (numberFailure: NumberFormatException) {
            return DEFAULT_COVER_ART_SIZE_PX
        }
        if (asInt <= 0) {
            return DEFAULT_COVER_ART_SIZE_PX
        }
        return asInt
    }

    private fun resolveThumpDataOrThrow(): ThumpData {
        val rawContext: android.content.Context? = context
        if (rawContext == null) {
            throw IllegalStateException("ThumpCoverArtProvider has no Context")
        }
        val rawApplication: android.content.Context = rawContext.applicationContext
        if (rawApplication !is ThumpApplication) {
            throw IllegalStateException(
                "ThumpCoverArtProvider expected ThumpApplication; got "
                    + rawApplication::class.java.name
            )
        }
        return rawApplication.thumpData
    }

    /**
     * Synchronous variant of the openFile workflow used by tests / smoke harnesses that want
     * the bytes without going through the pipe. Not part of the ContentProvider contract.
     */
    fun fetchCoverArtBytesBlocking(artId: String, sizePx: Int): ByteArray {
        val thumpData: ThumpData = resolveThumpDataOrThrow()
        return runBlocking {
            thumpData.getCoverArtBytes(artId, sizePx)
        }
    }

    companion object {
        const val DEFAULT_COVER_ART_SIZE_PX: Int = 300
        const val CONTENT_AUTHORITY: String = "com.therobm.thump.coverart"
    }
}

package com.therobm.thump.data

import java.io.InputStream

/**
 * Result of opening an audio stream for a track. The IProtocol layer owns the underlying HTTP
 * connection; ThumpData closes the stream when its DataSource.close fires.
 */
data class AudioStreamResponse(
    val inputStream: InputStream,
    val totalBytesAvailable: Long,
    val contentType: String?,
)

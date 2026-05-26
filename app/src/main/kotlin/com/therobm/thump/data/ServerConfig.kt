package com.therobm.thump.data

/**
 * The server-config row persisted in ThumpData's SQLite database. One row per process; updated
 * via `setServerConfig` and read via `getServerConfig`.
 *
 * `detectedProtocol` is the result of the Pulse detection probe at connect time. Null means
 * the probe has not been run yet for this URL.
 */
data class ServerConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val useTokenAuth: Boolean,
    val detectedProtocol: DetectedProtocol?,
    val lastProbedAtEpochMillis: Long?,
)

enum class DetectedProtocol {
    Subsonic,
    Pulse,
}

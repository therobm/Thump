package com.therobm.thump.subsonic

/**
 * The outcome of a Subsonic call.
 *
 * Subsonic reports errors inside the response envelope rather than via HTTP status codes, so a
 * 200 response can still carry an error. This type forces callers to discriminate the two.
 */
sealed interface SubsonicResult<out T> {
    data class Ok<T>(val value: T) : SubsonicResult<T>
    data class ServerError(val code: Int, val message: String) : SubsonicResult<Nothing>
    data class TransportError(val cause: Throwable) : SubsonicResult<Nothing>
    data class MalformedResponse(val cause: Throwable) : SubsonicResult<Nothing>
}

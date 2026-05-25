package com.therobm.thump.detail

import com.therobm.thump.subsonic.SubsonicResult

/**
 * Shared formatters used across all three detail screens. Plain top-level functions because
 * they read no state.
 */

/**
 * Render the failure branch of a SubsonicResult as a short human-readable string.
 */
fun describeSubsonicFailure(failedResult: SubsonicResult<*>): String {
    when (failedResult) {
        is SubsonicResult.Ok -> {
            return "Unexpected success branch"
        }
        is SubsonicResult.ServerError -> {
            return "Server error " + failedResult.code + ": " + failedResult.message
        }
        is SubsonicResult.TransportError -> {
            return "Network error: " + failedResult.cause.javaClass.simpleName
        }
        is SubsonicResult.MalformedResponse -> {
            return "Bad response: " + failedResult.cause.javaClass.simpleName
        }
    }
}

/**
 * Format a duration given in whole seconds as either "M:SS" or "H:MM:SS".
 */
fun formatDurationSeconds(totalSeconds: Int?): String {
    if (totalSeconds == null || totalSeconds <= 0) {
        return ""
    }
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val builder = StringBuilder()
    if (hours > 0) {
        builder.append(hours)
        builder.append(':')
        if (minutes < 10) {
            builder.append('0')
        }
        builder.append(minutes)
    } else {
        builder.append(minutes)
    }
    builder.append(':')
    if (seconds < 10) {
        builder.append('0')
    }
    builder.append(seconds)
    return builder.toString()
}

fun textOrFallback(input: String?, fallback: String): String {
    if (input == null || input.isEmpty()) {
        return fallback
    }
    return input
}

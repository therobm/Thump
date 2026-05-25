package com.therobm.thump.detail

/**
 * Shared three-way load state for the detail screens.
 *
 * Each detail screen tracks one in-flight fetch and renders one of three states. The error
 * branch carries a human-readable message so the screen can show what went wrong without the
 * caller having to reproduce a message-from-result helper for every screen.
 */
sealed interface DetailLoadState<out T> {
    object Loading : DetailLoadState<Nothing>
    data class Loaded<T>(val value: T) : DetailLoadState<T>
    data class Failed(val message: String) : DetailLoadState<Nothing>
}

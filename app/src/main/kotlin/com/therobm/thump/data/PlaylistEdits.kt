package com.therobm.thump.data

/**
 * Edits the caller wants applied to a playlist on the server. Any field whose value is null is
 * left unchanged. `trackIdsToAdd` are appended in order; `trackIndicesToRemove` are removed
 * from the playlist by their current zero-based position before any additions are applied.
 */
data class PlaylistEdits(
    val newName: String?,
    val newComment: String?,
    val trackIdsToAdd: List<String>,
    val trackIndicesToRemove: List<Int>,
)

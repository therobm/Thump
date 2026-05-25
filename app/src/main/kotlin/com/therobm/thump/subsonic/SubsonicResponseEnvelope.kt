package com.therobm.thump.subsonic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every Subsonic JSON response is wrapped in a single root key called "subsonic-response".
 *
 * The hyphen in the wire name makes a Kotlin property name impossible, so this is the one place
 * where a SerialName annotation is unavoidable. Inside the inner envelope, field names match
 * the wire exactly.
 */
@Serializable
data class SubsonicResponseRoot(
    @SerialName("subsonic-response")
    val subsonicResponse: SubsonicResponseEnvelope,
)

/**
 * The common fields present on every Subsonic response plus the optional payload fields used
 * by the endpoints the client currently calls.
 *
 * Each endpoint populates one of the payload fields and leaves the rest null. They all live on
 * the same envelope so a single decode pass handles every standard endpoint that wraps its
 * payload in subsonic-response.
 */
@Serializable
data class SubsonicResponseEnvelope(
    val status: String,
    val version: String,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean? = null,
    val error: SubsonicErrorPayload? = null,
    val albumList2: StandardAlbumList2Payload? = null,
    val playlists: StandardPlaylistsPayload? = null,
    val starred2: StandardStarred2Payload? = null,
)

@Serializable
data class SubsonicErrorPayload(
    val code: Int,
    val message: String,
)

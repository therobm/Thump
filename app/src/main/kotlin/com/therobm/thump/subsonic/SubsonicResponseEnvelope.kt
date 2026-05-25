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
 * The common fields present on every Subsonic response, regardless of which endpoint was called.
 *
 * Endpoint-specific payloads are decoded separately by reading the same JSON a second time into
 * the relevant payload class. Mixing them into this envelope would require a discriminator the
 * wire protocol does not have.
 */
@Serializable
data class SubsonicResponseEnvelope(
    val status: String,
    val version: String,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean? = null,
    val error: SubsonicErrorPayload? = null,
)

@Serializable
data class SubsonicErrorPayload(
    val code: Int,
    val message: String,
)

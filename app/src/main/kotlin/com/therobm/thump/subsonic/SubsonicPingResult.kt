package com.therobm.thump.subsonic

/**
 * The information learned from a successful ping call.
 *
 * The Subsonic ping endpoint itself only returns the standard envelope, but that envelope tells
 * us the protocol version the server speaks, the server brand and version when available, and
 * whether the server has signed itself up as an OpenSubsonic implementation.
 */
data class SubsonicPingResult(
    val protocolVersion: String,
    val serverType: String?,
    val serverVersion: String?,
    val isOpenSubsonicServer: Boolean,
)

package com.therobm.thump.data

/**
 * What `ping` returns. The protocol version and brand are reported by the server; whether the
 * server signed itself up as OpenSubsonic-compliant is in `isOpenSubsonicServer`.
 */
data class ServerInfo(
    val protocolVersion: String,
    val serverType: String?,
    val serverVersion: String?,
    val isOpenSubsonicServer: Boolean,
)

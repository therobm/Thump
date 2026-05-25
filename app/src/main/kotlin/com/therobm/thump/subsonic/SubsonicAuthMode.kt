package com.therobm.thump.subsonic

/**
 * Which authentication style to use when calling the server.
 *
 * Token is the modern Subsonic auth: send a random per-request salt and md5(password + salt) as
 * the token. The server never sees the password in transit.
 *
 * Legacy sends the password directly (hex-encoded with the "enc:" prefix). Use only for servers
 * that have not been updated to support token auth.
 */
sealed interface SubsonicAuthMode {
    object Token : SubsonicAuthMode
    object Legacy : SubsonicAuthMode
}

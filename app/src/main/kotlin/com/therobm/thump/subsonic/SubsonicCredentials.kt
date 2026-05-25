package com.therobm.thump.subsonic

/**
 * The information needed to talk to an OpenSubsonic server.
 *
 * Server URL is the bare server origin (e.g. "https://music.example.com"). The client appends
 * "/rest/<method>" itself; callers should not include the rest path here.
 */
data class SubsonicCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
)

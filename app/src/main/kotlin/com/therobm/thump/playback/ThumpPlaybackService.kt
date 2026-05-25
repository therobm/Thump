package com.therobm.thump.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts the ExoPlayer and its MediaSession.
 *
 * Running playback inside a foreground service is what lets audio survive the user backgrounding
 * the app and gives Android the lock-screen / system notification surface to render transport
 * controls into. The app process connects to this service via a MediaController and never holds
 * the player directly.
 */
class ThumpPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        val sessionSnapshot = mediaSession
        if (sessionSnapshot != null) {
            sessionSnapshot.player.release()
            sessionSnapshot.release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

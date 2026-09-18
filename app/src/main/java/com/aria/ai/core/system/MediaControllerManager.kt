package com.aria.ai.core.system

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport + volume control for the media agent.
 *
 * Playback commands are dispatched as hardware media-key events (the same path a
 * headset button takes, so every player honours them) while volume is driven
 * through [AudioManager]. "Now playing" is read from the active media sessions,
 * which is only possible once notification access has been granted.
 */
@Singleton
class MediaControllerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val sessions: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)

    fun playPause(): String {
        dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        return "Toggled playback."
    }

    fun next(): String {
        dispatchKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        return "Skipped to the next track."
    }

    fun previous(): String {
        dispatchKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return "Went back a track."
    }

    fun stopPlayback(): String {
        dispatchKey(KeyEvent.KEYCODE_MEDIA_STOP)
        return "Stopped playback."
    }

    fun volumeUp(): String {
        audio?.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_RAISE,
            AudioManager.FLAG_SHOW_UI
        )
        return "Volume up — now at ${currentVolumePercent()}%."
    }

    fun volumeDown(): String {
        audio?.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        return "Volume down — now at ${currentVolumePercent()}%."
    }

    fun setVolumePercent(percent: Int): String {
        val manager = audio ?: return "Audio service unavailable on this device."
        val target = percent.coerceIn(0, 100)
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = (max * target) / 100
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $target%."
    }

    fun mute(): String {
        audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        return "Muted."
    }

    fun unmute(): String {
        audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        return "Unmuted."
    }

    fun currentVolumePercent(): Int {
        val manager = audio ?: return 0
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((current * 100) / max).coerceIn(0, 100)
    }

    /** Title/artist of the active session, when media-session access is granted. */
    fun nowPlaying(): String {
        val manager = sessions ?: return "Media session service unavailable."
        val active = runCatching {
            manager.getActiveSessions(ComponentName(context, NotificationReader::class.java))
        }.getOrNull()

        val metadata = active?.firstOrNull()?.metadata
            ?: return "Nothing is playing right now."

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val playback = active.firstOrNull()?.playbackState

        return when {
            title.isBlank() -> "Something is playing, but it has no title metadata."
            artist.isBlank() -> "Now playing: $title"
            playback != null -> "Now playing: $title — $artist"
            else -> "Now playing: $title — $artist"
        }
    }

    private fun dispatchKey(keyCode: Int) {
        val manager = audio ?: return
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
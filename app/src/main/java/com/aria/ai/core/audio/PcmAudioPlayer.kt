package com.aria.ai.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PCM playback on top of the platform [AudioTrack].
 *
 * Used for any raw audio Aria produces locally (responses decoded from a
 * realtime websocket). [prepare] is idempotent for a given sample rate, so a
 * stream can be written chunk-by-chunk, and [stopAll] tears the track down when
 * the session ends. Failures never throw — they return false.
 */
@Singleton
class PcmAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val lock = Any()

    private var track: AudioTrack? = null
    private var preparedRate = 0

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Opens (or reuses) a streaming track for [sampleRate] mono PCM-16. */
    fun prepare(sampleRate: Int = SAMPLE_RATE): Boolean {
        synchronized(lock) {
            val existing = track
            if (existing != null && preparedRate == sampleRate &&
                existing.state == AudioTrack.STATE_INITIALIZED
            ) {
                return true
            }

            releaseLocked()

            val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
            if (minBufferBytes <= 0) {
                Log.w(TAG, "AudioTrack rejected $sampleRate Hz mono PCM-16")
                return false
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(CHANNEL)
                .setEncoding(ENCODING)
                .build()

            val created = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBufferBytes, sampleRate * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrElse { failure ->
                Log.e(TAG, "AudioTrack creation failed (${failure.javaClass.simpleName})")
                return false
            }

            if (created.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { created.release() }
                return false
            }

            val started = runCatching {
                created.play()
                created.playState == AudioTrack.PLAYSTATE_PLAYING
            }.getOrDefault(false)

            if (!started) {
                runCatching { created.release() }
                return false
            }

            track = created
            preparedRate = sampleRate
            _playing.value = true
            return true
        }
    }

    /**
     * Blocks until [samples] have been queued. Call [prepare] first; this returns
     * false when no track is available.
     */
    fun write(samples: ShortArray): Boolean {
        if (samples.isEmpty()) return true
        val active = synchronized(lock) { track } ?: return false
        val written = runCatching {
            active.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        }.getOrDefault(-1)
        return written >= 0
    }

    /** Pauses playback but keeps the track open for the next chunk. */
    fun pause() {
        synchronized(lock) { track }?.let { active -> runCatching { active.pause() } }
        _playing.value = false
    }

    /** Resumes a paused track. */
    fun resume() {
        val active = synchronized(lock) { track } ?: return
        runCatching { active.play() }
        _playing.value = true
    }

    /** Flushes queued audio and releases the track. */
    fun stopAll() {
        synchronized(lock) { releaseLocked() }
        _playing.value = false
    }

    private fun releaseLocked() {
        val active = track
        track = null
        preparedRate = 0
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.flush() }
            runCatching { active.release() }
        }
    }

    companion object {
        const val TAG = "AriaPlayer"

        /** Playback rate used by the realtime sockets' PCM-16 output. */
        const val SAMPLE_RATE = 24_000

        private const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
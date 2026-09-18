package com.aria.ai.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.aria.ai.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Raw 16 kHz PCM capture on top of the platform [AudioRecord].
 *
 * Frames are read on a background dispatcher, handed to [onChunk] and then
 * recycled through [AudioBufferPool]. The running RMS level is published as
 * [amplitude] (0..1) so the home HUD waveform animates without polling the mic.
 *
 * The recorder never throws: a missing permission, an unavailable source or a
 * failed initialisation all simply return false / stop the loop.
 */
@Singleton
class PcmAudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pool: AudioBufferPool,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val lock = Any()

    private var record: AudioRecord? = null
    private var captureJob: Job? = null

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    val isRecording: Boolean get() = _recording.value

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts streaming capture. [onChunk] receives every PCM-16 frame and must
     * return quickly — it is called on the capture dispatcher.
     */
    fun start(sampleRate: Int = SAMPLE_RATE, onChunk: (ShortArray) -> Unit): Boolean {
        if (isRecording) return true
        if (!hasPermission()) {
            Log.w(TAG, "Microphone permission not granted; capture not started")
            return false
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBufferBytes <= 0) {
            Log.w(TAG, "AudioRecord rejected $sampleRate Hz mono PCM-16")
            return false
        }

        val chunkSamples = (sampleRate / FRAMES_PER_SECOND).coerceAtLeast(MIN_CHUNK_SAMPLES)
        val bufferBytes = maxOf(minBufferBytes, chunkSamples * 2 * BUFFER_MULTIPLIER)

        val created = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                CHANNEL,
                ENCODING,
                bufferBytes
            )
        }.getOrElse { failure ->
            Log.e(TAG, "AudioRecord creation failed (${failure.javaClass.simpleName})")
            return false
        }

        if (created.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord did not initialise")
            runCatching { created.release() }
            return false
        }

        val started = runCatching {
            created.startRecording()
            created.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)

        if (!started) {
            Log.e(TAG, "startRecording() refused")
            runCatching { created.release() }
            return false
        }

        val job = scope.launch(Dispatchers.IO) {
            captureLoop(created, chunkSamples, onChunk)
        }

        synchronized(lock) {
            record = created
            captureJob = job
        }
        _recording.value = true
        return true
    }

    private suspend fun captureLoop(active: AudioRecord, chunkSamples: Int, onChunk: (ShortArray) -> Unit) {
        val buffer = pool.acquire(chunkSamples)
        try {
            while (currentCoroutineContext().isActive) {
                val read = active.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        break
                    }
                    continue
                }
                val frame = if (read == buffer.size) buffer else buffer.copyOf(read)
                _amplitude.value = rms(frame)
                runCatching { onChunk(frame) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Capture loop ended (${t.javaClass.simpleName})")
        } finally {
            _amplitude.value = 0f
            pool.release(buffer)
        }
    }

    /** Stops capture and returns the microphone to the system. */
    fun stop() {
        val (active, job) = synchronized(lock) {
            val currentRecord = record
            val currentJob = captureJob
            record = null
            captureJob = null
            currentRecord to currentJob
        }

        job?.cancel()
        runCatching { active?.stop() }
        runCatching { active?.release() }
        pool.clear()

        _recording.value = false
        _amplitude.value = 0f
    }

    /** Root-mean-square level of a PCM-16 frame, normalised to 0..1. */
    fun rms(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (sample in frame) {
            val value = sample / 32768.0
            sum += value * value
        }
        return sqrt(sum / frame.size).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val TAG = "AriaRecorder"

        /** Voice pipeline rate: 16 kHz mono PCM-16. */
        const val SAMPLE_RATE = 16_000

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAMES_PER_SECOND = 20
        private const val MIN_CHUNK_SAMPLES = 320
        private const val BUFFER_MULTIPLIER = 4
    }
}
package com.aria.ai.core.ml

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Hands-free activation for Aria.
 *
 * Two layers, both pure Kotlin:
 *  1. an always-available energy gate over the live 16 kHz frame stream (used by
 *     [AudioBridge] to detect a spoken burst — no model file required);
 *  2. an optional TensorFlow Lite audio classifier: drop a wake-word `.tflite`
 *     model into app storage and [loadModel] turns the same detector into a
 *     real phrase spotter via the Kotlin task API.
 */
@Singleton
class WakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Outcome of analysing audio. */
    sealed interface Detection {
        val confidence: Float

        /** A loud-enough burst of speech was detected (model-free path). */
        data class Energy(override val confidence: Float) : Detection

        /** The TFLite model matched a phrase. */
        data class Phrase(override val confidence: Float, val label: String) : Detection
    }

    private val lock = Any()

    private var classifier: AudioClassifier? = null
    private var loadedPath: String? = null

    private val _lastDetection = MutableStateFlow<Detection?>(null)
    val lastDetection: StateFlow<Detection?> = _lastDetection.asStateFlow()

    fun loadModel(modelPath: String): Boolean = synchronized(lock) {
        if (classifier != null && loadedPath == modelPath) return true
        if (!File(modelPath).exists()) {
            Log.w(TAG, "Wake-word model not found at $modelPath")
            return false
        }
        return try {
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(MODEL_SCORE_THRESHOLD)
                .build()
            val created = AudioClassifier.createFromFileAndOptions(context, modelPath, options)
            closeLocked()
            classifier = created
            loadedPath = modelPath
            Log.i(TAG, "Wake-word model loaded")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Wake-word model rejected (${t.javaClass.simpleName})")
            false
        }
    }

    fun isModelLoaded(): Boolean = synchronized(lock) { classifier != null }

    fun loadedModelPath(): String? = synchronized(lock) { loadedPath }

    fun unload() = synchronized(lock) { closeLocked() }

    /**
     * Frame-level gate for the streaming capture loop. Returns a detection when the
     * frame carries enough energy to be speech; null otherwise.
     */
    fun evaluate(frame: ShortArray, sampleRate: Int = SAMPLE_RATE): Detection? {
        if (frame.isEmpty() || sampleRate <= 0) return null
        val level = rms(frame)
        if (level < ENERGY_THRESHOLD) return null
        return Detection.Energy(level).also { _lastDetection.value = it }
    }

    /**
     * Runs one classification pass with the loaded model. This borrows the
     * microphone directly, so call it when the streaming capture is paused.
     */
    fun classifyLive(): Detection? {
        val model = synchronized(lock) { classifier } ?: return null
        var record: AudioRecord? = null
        return try {
            val input = model.createInputTensorAudio()
            record = model.createAudioRecord()
            record.startRecording()
            input.load(record)
            val category = model.classify(input).firstOrNull()?.categories?.firstOrNull()
            category?.let { best ->
                Detection.Phrase(confidence = best.score, label = best.label)
                    .also { _lastDetection.value = it }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Live classification failed (${t.javaClass.simpleName})")
            null
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    /** Root-mean-square amplitude of a PCM-16 frame, normalised to 0..1. */
    fun rms(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (sample in frame) {
            val value = sample / 32768.0
            sum += value * value
        }
        return sqrt(sum / frame.size).toFloat()
    }

    private fun closeLocked() {
        runCatching { classifier?.close() }
        classifier = null
        loadedPath = null
    }

    private companion object {
        const val TAG = "AriaWakeWord"
        const val SAMPLE_RATE = 16_000
        const val ENERGY_THRESHOLD = 0.055f
        const val MAX_RESULTS = 3
        const val MODEL_SCORE_THRESHOLD = 0.35f
    }
}
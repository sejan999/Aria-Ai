package com.aria.ai.core.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands-free wake-word detector for the always-listening service.
 *
 * NOTE ON NAMING: this is intentionally **not** called `WakeWordDetector`.
 * `com.aria.ai.core.ml.WakeWordDetector` already exists and is the energy-gate
 * detector consumed by [AudioBridge]; two classes with the same simple name in
 * one module made kapt emit an ERROR type for the always-listening path
 * (`InjectProcessingStep was unable to process ... could not be resolved`).
 *
 * Uses a TensorFlow Lite audio classifier model. The model file must exist in
 * app assets as `wakeword_model.tflite`. If it is absent,
 * [initialize] degrades gracefully to `false` and [start] becomes a no-op, so
 * the listener service stays alive without the phrase spotter.
 */
@Singleton
class ListenerWakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AriaWakeWordAudio"
        private const val MODEL_ASSET_NAME = "wakeword_model.tflite"
        private const val SCORE_THRESHOLD = 0.7f
        private const val MAX_RESULTS = 3
        private const val WAKE_WORD_LABEL = "hey_aria"
    }

    private var classifier: AudioClassifier? = null
    private var isRunning = false
    private var detectionThread: Thread? = null

    /**
     * Copy the model from assets into a readable location and initialize the
     * classifier. Returns `false` (gracefully) when no model is available.
     */
    fun initialize(): Boolean {
        if (classifier != null) return true
        val modelFile = copyModelFromAssets()
        if (modelFile == null) {
            Log.w(TAG, "Wake-word model not found in assets; phrase spotting disabled")
            return false
        }
        return try {
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setScoreThreshold(SCORE_THRESHOLD)
                .setMaxResults(MAX_RESULTS)
                .build()
            classifier = AudioClassifier.createFromFileAndOptions(context, modelFile.absolutePath, options)
            Log.i(TAG, "Wake-word model initialized from $MODEL_ASSET_NAME")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Wake-word model rejected (${t.javaClass.simpleName})")
            false
        }
    }

    /** Whether a model was loaded successfully. */
    fun isReady(): Boolean = classifier != null

    /**
     * Start listening for the wake word on a background thread.
     * Calls [onWakeWordDetected] each time the phrase is recognised.
     */
    fun start(onWakeWordDetected: () -> Unit) {
        if (isRunning) return
        val model = classifier
        if (model == null) {
            Log.w(TAG, "Wake-word detector not initialized; ignoring start request")
            return
        }
        isRunning = true
        detectionThread = Thread({
            try {
                val audioRecord = model.createAudioRecord()
                audioRecord.startRecording()
                while (isRunning) {
                    val input = model.createInputTensorAudio()
                    input.load(audioRecord)
                    val results = model.classify(input)
                    val categories = results.firstOrNull()?.categories.orEmpty()
                    if (categories.any { it.label == WAKE_WORD_LABEL && it.score >= SCORE_THRESHOLD }) {
                        onWakeWordDetected()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Wake-word detection thread failed (${t.javaClass.simpleName})")
            } finally {
                isRunning = false
                detectionThread = null
            }
        }, "AriaWakeWordDetection")
        detectionThread?.start()
    }

    /** Stop wake-word detection and release the classifier. */
    fun stop() {
        isRunning = false
        detectionThread?.let { thread -> runCatching { thread.join(1_500) } }
        detectionThread = null
        runCatching { classifier?.close() }
        classifier = null
    }

    private fun copyModelFromAssets(): File? {
        val fd = runCatching { context.assets.openFd(MODEL_ASSET_NAME) }.getOrNull()
            ?: return null
        try {
            val dest = File(context.filesDir, MODEL_ASSET_NAME)
            if (dest.exists() && dest.length() > 0) return dest
            fd.createInputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return dest
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy wake-word model from assets (${t.javaClass.simpleName})")
            return null
        } finally {
            runCatching { fd.close() }
        }
    }
}

package com.aria.ai.core.ml

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe LLM Inference wrapper for on-device Gemma models.
 *
 * This is the "offline brain": no network, no API key, no native code in this
 * repository — the runtime ships as a prebuilt Kotlin/Java task library. The
 * engine is created lazily, kept warm between turns and torn down on demand.
 */
@Singleton
class OnDeviceLlm @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val lock = Any()

    private var engine: LlmInference? = null
    private var loadedPath: String? = null

    fun isLoaded(): Boolean = synchronized(lock) { engine != null }

    fun loadedModelPath(): String? = synchronized(lock) { loadedPath }

    /** Creates (or reuses) the engine for [modelPath]. */
    fun ensureLoaded(modelPath: String): LlmInference = synchronized(lock) {
        val existing = engine
        if (existing != null && loadedPath == modelPath) return existing
        if (existing != null) closeLocked()

        if (!File(modelPath).exists()) {
            throw IllegalArgumentException("Model file not found at $modelPath")
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(DEFAULT_TEMPERATURE)
            .setTopK(TOP_K)
            .setRandomSeed(RANDOM_SEED)
            .build()

        val created = try {
            LlmInference.createFromOptions(context, options)
        } catch (t: Throwable) {
            Log.e(TAG, "MediaPipe engine init failed (${t.javaClass.simpleName})")
            throw t
        }

        engine = created
        loadedPath = modelPath
        Log.i(TAG, "On-device model ready")
        created
    }

    /** Blocking single-shot generation (call from Dispatchers.IO). */
    fun generate(modelPath: String, prompt: String): String {
        val inference = ensureLoaded(modelPath)
        return inference.generateResponse(prompt).orEmpty()
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }

    /**
     * Streams a generation token-by-token.
     *
     * MediaPipe binds its progress listener at engine-creation time, so this path
     * builds a short-lived engine, forwards every new fragment through [onPartial]
     * (the listener reports the accumulated text) and closes it when the turn ends.
     */
    fun generateStreaming(modelPath: String, prompt: String, onPartial: (String) -> Unit): String {
        if (!File(modelPath).exists()) {
            throw IllegalArgumentException("Model file not found at $modelPath")
        }

        val latest = java.util.concurrent.atomic.AtomicReference("")
        val reported = java.util.concurrent.atomic.AtomicInteger(0)
        val finished = java.util.concurrent.CountDownLatch(1)

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(DEFAULT_TEMPERATURE)
            .setTopK(TOP_K)
            .setRandomSeed(RANDOM_SEED)
            .setResultListener { partial, done ->
                val text = partial.orEmpty()
                latest.set(text)
                val alreadySent = reported.get()
                if (text.length > alreadySent) {
                    onPartial(text.substring(alreadySent))
                    reported.set(text.length)
                }
                if (done) finished.countDown()
            }
            .setErrorListener { error ->
                Log.e(TAG, "MediaPipe stream error (${error.javaClass.simpleName})")
            }
            .build()

        val engine = LlmInference.createFromOptions(context, options)
        return try {
            engine.generateResponseAsync(prompt)
            finished.await(GENERATION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            latest.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            latest.get()
        } finally {
            runCatching { engine.close() }
        }
    }

    private companion object {
        const val TAG = "AriaOnDeviceLlm"
        const val MAX_TOKENS = 512
        const val DEFAULT_TEMPERATURE = 0.8f
        const val TOP_K = 40
        const val RANDOM_SEED = 7
        const val GENERATION_TIMEOUT_SECONDS = 120L
    }
}
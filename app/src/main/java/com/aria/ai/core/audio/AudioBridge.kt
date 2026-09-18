package com.aria.ai.core.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.aria.ai.core.ml.WakeWordDetector
import com.aria.ai.core.network.GeminiLiveSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aria Ai voice front-end, assembled only from official Android APIs:
 * [PcmAudioRecorder] for raw PCM, the platform [SpeechRecognizer] for the
 * one-utterance STT flow, [PcmAudioPlayer] for PCM replies and
 * [WakeWordDetector] for hands-free activation.
 */
@Singleton
class AudioBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: PcmAudioRecorder,
    private val player: PcmAudioPlayer,
    private val wakeWordDetector: WakeWordDetector,
    private val liveSocket: GeminiLiveSocket
) {

    /** Progress of one speech-recognition pass. */
    data class SpeechEvent(
        val partial: String? = null,
        val finalText: String? = null,
        val error: String? = null
    ) {
        val isFinal: Boolean get() = finalText != null
    }

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _wakeEvents = MutableStateFlow<WakeWordDetector.Detection?>(null)
    val wakeEvents: StateFlow<WakeWordDetector.Detection?> = _wakeEvents.asStateFlow()

    /** Live microphone energy for the Compose waveform. */
    val amplitude: StateFlow<Float> get() = recorder.amplitude

    /** Raw 16 kHz capture, also feeding the wake-word detector. */
    fun startPcmCapture(onChunk: (ShortArray) -> Unit = {}): Boolean {
        val started = recorder.start(PcmAudioRecorder.SAMPLE_RATE) { chunk ->
            onChunk(chunk)
            wakeWordDetector.evaluate(chunk, PcmAudioRecorder.SAMPLE_RATE)
                ?.let { _wakeEvents.value = it }
        }
        _listening.value = started
        return started
    }

    fun stopPcmCapture() {
        recorder.stop()
        _listening.value = false
    }

    /**
     * Listen for a single utterance. Partial results stream out as they arrive so
     * the HUD can render speech live; the recognizer is bound to the main looper
     * and always torn down in [awaitClose].
     */
    fun listenOnce(languageTag: String = "en-US"): Flow<SpeechEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SpeechEvent(error = "Speech recognition is not available on this device"))
            close()
            return@callbackFlow
        }

        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null

        mainHandler.post {
            val created = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = created
            created.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() {
                    _listening.value = true
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    _listening.value = false
                }

                override fun onError(error: Int) {
                    _listening.value = false
                    trySend(SpeechEvent(error = describeError(error)))
                    close()
                }

                override fun onResults(results: Bundle?) {
                    _listening.value = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    trySend(SpeechEvent(finalText = text))
                    close()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) trySend(SpeechEvent(partial = partial))
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            created.startListening(buildIntent(languageTag))
        }

        awaitClose {
            mainHandler.post {
                runCatching { recognizer?.stopListening() }
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
            _listening.value = false
        }
    }.flowOn(Dispatchers.Main)

    private fun buildIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error while listening"
        SpeechRecognizer.ERROR_CLIENT -> "Speech client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
        SpeechRecognizer.ERROR_NETWORK -> "Network error during recognition"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Recognition timed out"
        SpeechRecognizer.ERROR_NO_MATCH -> "I could not make out what you said"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer was busy"
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not hear anything"
        else -> "Speech recognition failed (code $code)"
    }

    // ------------------------------------------------------------- PCM playback

    fun playPcm(samples: ShortArray, sampleRate: Int = PcmAudioPlayer.SAMPLE_RATE): Boolean {
        if (!player.prepare(sampleRate)) return false
        return player.write(samples)
    }

    fun stopPlayback() = player.stopAll()

    // -------------------------------------------------- full-duplex live stream

    /** Live events surfaced by the Gemini Live session. */
    val liveEvents: Flow<GeminiLiveSocket.LiveEvent?> get() = liveFlow

    private val liveFlow = MutableStateFlow<GeminiLiveSocket.LiveEvent?>(null)

    private var streamingJob: kotlinx.coroutines.Job? = null
    private var streamingScope: kotlinx.coroutines.CoroutineScope? = null

    /**
     * Opens a full-duplex Gemini Live session: starts the PCM recorder and
     * forwards every captured chunk into the live socket while relaying model
     * audio back through [PcmAudioPlayer]. Returns `true` when capture started.
     */
    fun startStreaming(sampleRate: Int = GeminiLiveSocket.INPUT_SAMPLE_RATE): Boolean {
        if (streamingJob?.isActive == true) return true
        if (!hasRecordPermission()) return false

        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        streamingScope = scope

        val captureStarted = recorder.start(sampleRate) { chunk ->
            liveSocket.sendPcm(chunk, sampleRate)
        }
        if (!captureStarted) {
            scope.cancel()
            streamingScope = null
            return false
        }

        streamingJob = scope.launch {
            liveSocket.connect()
                .collect { event ->
                    liveFlow.value = event
                    if (event is GeminiLiveSocket.LiveEvent.Audio) {
                        val samples = ShortArray(event.pcm16.size / 2)
                        java.nio.ByteBuffer.wrap(event.pcm16)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(samples)
                        if (player.prepare(event.sampleRate)) player.write(samples)
                    }
                }
        }
        return true
    }

    /** Tears down the live session and stops the recorder. */
    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        streamingScope?.cancel()
        streamingScope = null
        liveSocket.disconnect()
        recorder.stop()
        _listening.value = false
    }

    private fun hasRecordPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun shutdown() {
        stopStreaming()
        stopPcmCapture()
        stopPlayback()
    }
}
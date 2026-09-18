package com.aria.ai.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aria's voice output, built on the platform [TextToSpeech] engine.
 *
 * The engine is created once per process and its readiness is exposed as
 * [ready] so the Settings screen can report it. [speak] never throws: when the
 * engine is missing or still initialising it returns false and the turn simply
 * stays text-only — a broken TTS install must never break a conversation.
 */
@Singleton
class TtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TextToSpeech.OnInitListener {

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val lock = Any()
    private var engine: TextToSpeech? = null
    private var languageSet = false

    init {
        engine = runCatching { TextToSpeech(context.applicationContext, this) }
            .onFailure { failure ->
                Log.w(TAG, "Text-to-speech unavailable (${failure.javaClass.simpleName})")
            }
            .getOrNull()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS engine failed to initialise (status $status)")
            _ready.value = false
            return
        }
        sealedEngine()?.let { tts ->
            runCatching { applyLanguage(tts, Locale.getDefault()) }
            runCatching {
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _speaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _speaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _speaking.value = false
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _speaking.value = false
                    }
                })
            }
        }
        _ready.value = sealedEngine() != null
        Log.i(TAG, "TTS ready")
    }

    /** True once the engine reported success. */
    fun isReady(): Boolean = _ready.value

    /**
     * Speaks [text]. Queued utterances are flushed by default, because Aria's
     * replies supersede anything it was saying before.
     */
    fun speak(
        text: String,
        locale: Locale = Locale.getDefault(),
        flush: Boolean = true
    ): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val tts = sealedEngine() ?: return false

        if (!languageSet) {
            runCatching { applyLanguage(tts, locale) }
        }

        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = runCatching {
            tts.speak(clean.take(MAX_CHARS), mode, null, UUID.randomUUID().toString())
        }.getOrDefault(TextToSpeech.ERROR)

        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Utterance rejected by the TTS engine")
            return false
        }
        _speaking.value = true
        return true
    }

    /** Stops the current utterance immediately. */
    fun stop() {
        runCatching { sealedEngine()?.stop() }
        _speaking.value = false
    }

    /** Releases the engine (called when Aria shuts down). */
    fun shutdown() {
        val tts = synchronized(lock) {
            val current = engine
            engine = null
            current
        }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        _ready.value = false
        _speaking.value = false
        languageSet = false
    }

    private fun applyLanguage(tts: TextToSpeech, locale: Locale) {
        val available = tts.isLanguageAvailable(locale)
        val chosen = if (available >= TextToSpeech.LANG_AVAILABLE) locale else Locale.US
        val result = tts.setLanguage(chosen)
        languageSet = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun sealedEngine(): TextToSpeech? = synchronized(lock) { engine }

    private companion object {
        const val TAG = "AriaTts"

        /** Longest utterance handed to the platform engine at once. */
        const val MAX_CHARS = 3_500
    }
}
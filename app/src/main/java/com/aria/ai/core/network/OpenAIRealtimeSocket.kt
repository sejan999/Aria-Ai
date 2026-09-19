package com.aria.ai.core.network

import android.util.Base64
import com.aria.ai.core.ai.InvalidApiKeyException
import com.aria.ai.core.ai.KeyProvider
import com.aria.ai.core.ai.KeyRedactor
import com.aria.ai.core.ai.ModelDiscoveryFailedException
import com.aria.ai.core.ai.ModelResolver
import com.aria.ai.core.ai.PermissionDeniedException
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.discovery.OpenAIModelDiscovery
import com.aria.ai.core.ai.model.TaskType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI Realtime websocket client (speech-to-speech over a single socket).
 *
 * Complements [GeminiLiveSocket]: Aria can run either realtime backend with the
 * same push-to-talk UX. PCM is 24 kHz mono 16-bit on both directions.
 *
 * The realtime model id is **never hardcoded**: it comes from the OpenAI model
 * catalogue ranked for [TaskType.AUDIO_LIVE] (any id containing `realtime`) and
 * is cached for 24 hours.
 */
@Singleton
class OpenAIRealtimeSocket @Inject constructor(
    private val client: OkHttpClient,
    private val keys: KeyProvider,
    private val resolver: ModelResolver,
    private val selector: OpenAIModelDiscovery
) {

    /** Everything the Realtime API can tell Aria. */
    sealed interface RealtimeEvent {
        data object SessionReady : RealtimeEvent
        data class TextDelta(val value: String) : RealtimeEvent
        data class AudioDelta(val pcm16: ByteArray, val sampleRate: Int) : RealtimeEvent
        data class Transcript(val value: String, val fromUser: Boolean) : RealtimeEvent
        data class TurnComplete(val interrupted: Boolean) : RealtimeEvent
        data class Json(val raw: String) : RealtimeEvent
        data class Closed(val code: Int, val reason: String) : RealtimeEvent
        data class Failed(val message: String) : RealtimeEvent
    }

    private var socket: WebSocket? = null

    val isConnected: Boolean get() = socket != null

    /**
     * Opens a realtime session. The flow stays alive until the socket closes and
     * the socket is closed automatically when the collector is cancelled.
     */
    fun connect(
        model: String? = null,
        voice: String = DEFAULT_VOICE,
        instructions: String = DEFAULT_INSTRUCTIONS
    ): Flow<RealtimeEvent> = callbackFlow {
        val apiKey = keys.requireKey(ProviderIds.OPENAI, "OpenAI")
        // Auto-selected realtime model; an explicit override still wins.
        val realtimeModel = model?.takeIf { it.isNotBlank() }
            ?: resolver.resolveOrNull(selector, ProviderIds.OPENAI, apiKey, TaskType.AUDIO_LIVE)
            ?: throw ModelDiscoveryFailedException(
                ProviderIds.OPENAI,
                IllegalStateException(
                    "No OpenAI model advertises realtime audio for this key, so live voice is unavailable."
                )
            )
        val request = Request.Builder()
            .url("$WS_ENDPOINT?model=$realtimeModel")
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                webSocket.send(sessionFrame(instructions, voice))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text).forEach { event -> trySend(event) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                parse(bytes.utf8()).forEach { event -> trySend(event) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(RealtimeEvent.Closed(code, reason))
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                trySend(RealtimeEvent.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                // The handshake status tells us whether the credential was the problem.
                when (response?.code) {
                    401 -> trySend(
                        RealtimeEvent.Failed(InvalidApiKeyException(ProviderIds.OPENAI).message.orEmpty())
                    )

                    403 -> trySend(
                        RealtimeEvent.Failed(
                            PermissionDeniedException(
                                ProviderIds.OPENAI,
                                KeyRedactor.scrub(response.message.ifBlank { "realtime access not permitted" })
                            ).message.orEmpty()
                        )
                    )

                    else -> trySend(
                        RealtimeEvent.Failed(KeyRedactor.scrub(t.message ?: "realtime socket failure"))
                    )
                }
                close()
            }
        }

        socket = client.newWebSocket(request, listener)

        awaitClose {
            runCatching { socket?.close(NORMAL_CLOSURE, "aria session ended") }
            socket = null
        }
    }

    /** Updates the session configuration (voice, instructions, audio formats). */
    fun sendSessionUpdate(
        instructions: String = DEFAULT_INSTRUCTIONS,
        voice: String = DEFAULT_VOICE
    ): Boolean {
        val ws = socket ?: return false
        return ws.send(sessionFrame(instructions, voice))
    }

    /** Sends a text turn and asks the model for a response. */
    fun sendText(text: String): Boolean {
        val ws = socket ?: return false
        val item = JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "input_text").put("text", text))
                    )
            )
        val created = ws.send(item.toString())
        val asked = ws.send(JSONObject().put("type", "response.create").toString())
        return created && asked
    }

    /** Appends microphone PCM to the server-side input buffer (server VAD commits). */
    fun sendAudio(pcm16: ByteArray): Boolean {
        val ws = socket ?: return false
        return ws.send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(pcm16, Base64.NO_WRAP))
                .toString()
        )
    }

    /** Commits the mic buffer manually (push-to-talk release). */
    fun commitAudioBuffer(): Boolean {
        val ws = socket ?: return false
        return ws.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
    }

    fun createResponse(): Boolean {
        val ws = socket ?: return false
        return ws.send(JSONObject().put("type", "response.create").toString())
    }

    /** Interrupts the model mid-answer (user barge-in). */
    fun cancelResponse(): Boolean {
        val ws = socket ?: return false
        return ws.send(JSONObject().put("type", "response.cancel").toString())
    }

    fun close() {
        runCatching { socket?.close(NORMAL_CLOSURE, "aria closing") }
        socket = null
    }

    // ------------------------------------------------------------------ internals

    private fun sessionFrame(instructions: String, voice: String): String = JSONObject()
        .put("type", "session.update")
        .put(
            "session",
            JSONObject()
                .put("modalities", JSONArray().put("audio").put("text"))
                .put("instructions", instructions)
                .put("voice", voice)
                .put("input_audio_format", "pcm16")
                .put("output_audio_format", "pcm16")
                .put(
                    "input_audio_transcription",
                    JSONObject().put("model", TRANSCRIPTION_MODEL)
                )
                .put("turn_detection", JSONObject().put("type", "server_vad"))
        )
        .toString()

    private fun parse(raw: String): List<RealtimeEvent> {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return listOf(RealtimeEvent.Json(raw))

        val events = mutableListOf<RealtimeEvent>()

        when (json.optString("type", "")) {
            "session.created", "session.updated" -> events += RealtimeEvent.SessionReady

            "response.text.delta", "response.output_text.delta", "response.audio_transcript.delta" ->
                json.optString("delta", "")
                    .takeIf { it.isNotEmpty() }
                    ?.let { events += RealtimeEvent.TextDelta(it) }

            "response.audio.delta" -> {
                val bytes = runCatching {
                    Base64.decode(json.optString("delta", ""), Base64.DEFAULT)
                }.getOrDefault(ByteArray(0))
                if (bytes.isNotEmpty()) events += RealtimeEvent.AudioDelta(bytes, OUTPUT_SAMPLE_RATE)
            }

            "response.audio_transcript.done", "response.output_text.done" -> {
                val text = json.optString("transcript", json.optString("text", ""))
                if (text.isNotBlank()) events += RealtimeEvent.Transcript(text, false)
            }

            "conversation.item.input_audio_transcription.completed" ->
                json.optString("transcript", "")
                    .takeIf { it.isNotBlank() }
                    ?.let { events += RealtimeEvent.Transcript(it, true) }

            "response.done", "response.cancelled" -> events += RealtimeEvent.TurnComplete(false)

            "error" -> events += RealtimeEvent.Failed(
                KeyRedactor.scrub(
                    json.optJSONObject("error")?.optString("message", "realtime error")
                        ?: "realtime error"
                )
            )
        }

        if (events.isEmpty()) events += RealtimeEvent.Json(raw)
        return events
    }

    companion object {
        /**
         * The realtime model is NOT pinned here. It is discovered from the OpenAI
         * model catalogue and ranked for the AUDIO_LIVE task (ids containing
         * `realtime`), then cached for 24 hours by `ModelCache`.
         */

        const val DEFAULT_VOICE = "alloy"
        const val OUTPUT_SAMPLE_RATE = 24_000
        private const val WS_ENDPOINT = "wss://api.openai.com/v1/realtime"
        private const val NORMAL_CLOSURE = 1000
        private const val TRANSCRIPTION_MODEL = "whisper-1"
        private const val DEFAULT_INSTRUCTIONS =
            "You are Aria Ai, a voice-first Android assistant. Answer briefly and speak naturally."
    }
}
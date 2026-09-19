package com.aria.ai.core.network

import android.util.Base64
import com.aria.ai.core.ai.InvalidApiKeyException
import com.aria.ai.core.ai.KeyProvider
import com.aria.ai.core.ai.KeyRedactor
import com.aria.ai.core.ai.ModelDiscoveryFailedException
import com.aria.ai.core.ai.ModelResolver
import com.aria.ai.core.ai.PermissionDeniedException
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.discovery.GeminiModelDiscovery
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
 * Gemini Live (BidiGenerateContent) websocket client: a full-duplex voice loop
 * over one socket (raw PCM in, PCM/text out) with barge-in support through
 * `serverContent.interrupted`. Built from OkHttp + org.json only.
 *
 * The live model id is **never hardcoded**: it is discovered from the Gemini
 * ListModels endpoint and ranked for [TaskType.AUDIO_LIVE] (i.e. the model that
 * advertises `bidiGenerateContent`), then cached for 24 hours.
 */
@Singleton
class GeminiLiveSocket @Inject constructor(
    private val client: OkHttpClient,
    private val keys: KeyProvider,
    private val resolver: ModelResolver,
    private val selector: GeminiModelDiscovery
) {

    /** Everything the Live API can tell Aria. */
    sealed interface LiveEvent {
        data object Opened : LiveEvent
        data object SetupComplete : LiveEvent
        data class Text(val value: String) : LiveEvent
        data class Audio(val pcm16: ByteArray, val sampleRate: Int) : LiveEvent
        data class TurnComplete(val interrupted: Boolean) : LiveEvent
        data class Json(val raw: String) : LiveEvent
        data class Closed(val code: Int, val reason: String) : LiveEvent
        data class Failed(val message: String) : LiveEvent
    }

    private var socket: WebSocket? = null

    val isConnected: Boolean get() = socket != null

    /**
     * Sends one PCM-16 chunk as a `realtimeInput` media chunk. Safe to call
     * before the socket opens (silently dropped) and from any thread.
     */
    fun sendPcm(samples: ShortArray, sampleRate: Int = INPUT_SAMPLE_RATE) {
        val ws = socket ?: return
        val bytes = ByteArray(samples.size * 2)
        java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().put(samples)
        val payload = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "mediaChunks",
                JSONArray().put(
                    JSONObject()
                        .put("mimeType", "audio/pcm;rate=$sampleRate")
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                )
            )
        )
        ws.send(payload.toString())
    }

    /** Closes the current live session (idempotent). */
    fun disconnect() {
        runCatching { socket?.close(NORMAL_CLOSURE, null) }
        socket = null
    }

    /** Opens a session; the socket closes with the collector. */
    fun connect(
        model: String? = null,
        systemInstruction: String = DEFAULT_SYSTEM,
        modalities: List<String> = listOf("AUDIO")
    ): Flow<LiveEvent> = callbackFlow {
        val apiKey = keys.requireKey(ProviderIds.GEMINI, "Google Gemini")
        // Auto-selected realtime model; an explicit override still wins.
        val liveModel = model?.takeIf { it.isNotBlank() }
            ?: resolver.resolveOrNull(selector, ProviderIds.GEMINI, apiKey, TaskType.AUDIO_LIVE)
            ?: throw ModelDiscoveryFailedException(
                ProviderIds.GEMINI,
                IllegalStateException(
                    "No Gemini model advertises bidiGenerateContent for this key, so live voice is unavailable."
                )
            )
        val request = Request.Builder().url("$LIVE_WS_BASE?key=$apiKey").build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                trySend(LiveEvent.Opened)
                webSocket.send(setupFrame(liveModel, systemInstruction, modalities))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text).forEach { trySend(it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                parse(bytes.utf8()).forEach { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(LiveEvent.Closed(code, reason))
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                trySend(LiveEvent.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                // The handshake status tells us whether the credential was the problem.
                when (response?.code) {
                    401 -> trySend(
                        LiveEvent.Failed(InvalidApiKeyException(ProviderIds.GEMINI).message.orEmpty())
                    )

                    403 -> trySend(
                        LiveEvent.Failed(
                            PermissionDeniedException(
                                ProviderIds.GEMINI,
                                KeyRedactor.scrub(response.message.ifBlank { "live access not permitted" })
                            ).message.orEmpty()
                        )
                    )

                    else -> trySend(
                        LiveEvent.Failed(KeyRedactor.scrub(t.message ?: "live socket failure"))
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

    /** Sends a user turn as text. */
    fun sendText(text: String): Boolean {
        val ws = socket ?: return false
        val frame = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                )
                .put("turnComplete", true)
        )
        return ws.send(frame.toString())
    }

    /** Streams a chunk of 16-bit PCM captured from the microphone. */
    fun sendAudio(pcm16: ByteArray, sampleRate: Int = INPUT_SAMPLE_RATE): Boolean {
        val ws = socket ?: return false
        val frame = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "mediaChunks",
                JSONArray().put(
                    JSONObject()
                        .put("mimeType", "audio/pcm;rate=$sampleRate")
                        .put("data", Base64.encodeToString(pcm16, Base64.NO_WRAP))
                )
            )
        )
        return ws.send(frame.toString())
    }

    /** Signals the end of a microphone stream (push-to-talk release). */
    fun sendAudioStreamEnd(): Boolean {
        val ws = socket ?: return false
        return ws.send(
            JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()
        )
    }

    fun close() {
        runCatching { socket?.close(NORMAL_CLOSURE, "aria closing") }
        socket = null
    }

    // ------------------------------------------------------------------ internals

    private fun setupFrame(
        model: String,
        systemInstruction: String,
        modalities: List<String>
    ): String {
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray(modalities)))
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction))
                )
            )
        return JSONObject().put("setup", setup).toString()
    }

    private fun parse(raw: String): List<LiveEvent> {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return listOf(LiveEvent.Json(raw))

        val events = mutableListOf<LiveEvent>()
        if (json.has("setupComplete")) events += LiveEvent.SetupComplete

        json.optJSONObject("serverContent")?.let { content ->
            val modelTurn = content.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONObject("content")?.optJSONArray("parts")
                ?: modelTurn?.optJSONArray("parts")

            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue

                    part.optString("text", "")
                        .takeIf { it.isNotBlank() }
                        ?.let { events += LiveEvent.Text(it) }

                    part.optJSONObject("inlineData")?.let { inline ->
                        val mime = inline.optString("mimeType", "")
                        if (mime.startsWith("audio/")) {
                            val rate = mime.substringAfter("rate=", OUTPUT_SAMPLE_RATE.toString())
                                .toIntOrNull() ?: OUTPUT_SAMPLE_RATE
                            val bytes = runCatching {
                                Base64.decode(inline.optString("data", ""), Base64.DEFAULT)
                            }.getOrDefault(ByteArray(0))
                            if (bytes.isNotEmpty()) events += LiveEvent.Audio(bytes, rate)
                        }
                    }
                }
            }

            if (content.optBoolean("turnComplete", false)) {
                events += LiveEvent.TurnComplete(content.optBoolean("interrupted", false))
            }
        }

        json.optJSONObject("error")?.let { error ->
            events += LiveEvent.Failed(
                KeyRedactor.scrub(error.optString("message", "live api error"))
            )
        }

        if (events.isEmpty()) events += LiveEvent.Json(raw)
        return events
    }

    companion object {
        /**
         * The Live model is NOT pinned here. It is discovered from the Gemini
         * model catalogue and ranked for the AUDIO_LIVE task (any model whose
         * `supportedGenerationMethods` contains `bidiGenerateContent`), then
         * cached for 24 hours by `ModelCache`.
         */

        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000
        private const val NORMAL_CLOSURE = 1000

        /**
         * BidiGenerateContent websocket endpoint (v1beta).
         *
         * Authentication for the websocket is the API key as a **query
         * parameter** — that is the documented scheme for this endpoint (unlike
         * the REST API, which takes `x-goog-api-key`). It is safe here because
         * the OkHttp interceptor in `NetworkModule` logs only method, host, path
         * and status, so the query string can never reach logcat.
         */
        private const val LIVE_WS_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val DEFAULT_SYSTEM =
            "You are Aria Ai, a voice-first Android assistant. Answer briefly and speak naturally."
    }
}
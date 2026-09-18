package com.aria.ai.core.ai.adapters

import com.aria.ai.core.ai.AIProvider
import com.aria.ai.core.ai.AiHttp
import com.aria.ai.core.ai.KeyProvider
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.aggregateViaStream
import com.aria.ai.core.ai.model.ChatChunk
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.ChatResponse
import com.aria.ai.core.ai.model.Message
import com.aria.ai.core.ai.model.Role
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Gemini adapter (Generative Language REST API, SSE streaming).
 *
 * The key travels in the `x-goog-api-key` header instead of the query string so
 * it can never leak through a logged/echoed URL. Vision is supported through
 * `inline_data` base64 JPEG parts.
 */
@Singleton
class GeminiAdapter @Inject constructor(
    private val client: OkHttpClient,
    private val keys: KeyProvider
) : AIProvider {

    override val id: String = ProviderIds.GEMINI
    override val displayName: String = "Google Gemini"
    override val defaultModel: String = "gemini-1.5-flash"
    override val supportsVision: Boolean = true

    override suspend fun chat(messages: List<Message>, options: ChatOptions): ChatResponse =
        aggregateViaStream(this, messages, options)

    override fun streamChat(messages: List<Message>, options: ChatOptions): Flow<ChatChunk> {
        val apiKey = keys.requireKey(id, displayName)
        val model = options.model?.takeIf { it.isNotBlank() } ?: defaultModel
        val url = "$BASE_URL/$model:streamGenerateContent?alt=sse"
        val headers = mapOf(
            "x-goog-api-key" to apiKey,
            "Content-Type" to "application/json"
        ) + options.extraHeaders
        val payload = buildPayload(messages, options)
        return AiHttp.streamSse(client, url, headers, payload) { data -> parseChunk(data, model) }
    }

    private fun buildPayload(messages: List<Message>, options: ChatOptions): String {
        val contents = JSONArray()
        for (message in messages) {
            if (message.role == Role.SYSTEM) continue
            val parts = JSONArray()
            if (message.content.isNotBlank()) {
                parts.put(JSONObject().put("text", message.content))
            }
            val image = message.imageBase64
            if (!image.isNullOrBlank()) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", image)
                    )
                )
            }
            if (parts.length() == 0) parts.put(JSONObject().put("text", ""))
            contents.put(
                JSONObject()
                    .put("role", if (message.role == Role.ASSISTANT) "model" else "user")
                    .put("parts", parts)
            )
        }

        val root = JSONObject().put("contents", contents)
        val system = options.systemPrompt
            ?: messages.firstOrNull { it.role == Role.SYSTEM }?.content
        if (!system.isNullOrBlank()) {
            root.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
        }
        root.put(
            "generationConfig",
            JSONObject()
                .put("temperature", options.temperature.toDouble())
                .put("maxOutputTokens", options.maxTokens)
                .put("topP", options.topP.toDouble())
        )
        return root.toString()
    }

    private fun parseChunk(data: String, model: String): ChatChunk {
        val json = JSONObject(data)
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)

        val text = buildString {
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                ?: return@buildString
            for (index in 0 until parts.length()) {
                append(parts.optJSONObject(index)?.optString("text", "").orEmpty())
            }
        }

        val rawFinish = if (candidate == null || candidate.isNull("finishReason")) {
            ""
        } else {
            candidate.optString("finishReason", "")
        }
        val finish = rawFinish.takeIf { it.isNotBlank() && it != "FINISH_REASON_UNSPECIFIED" && it != "null" }

        return ChatChunk(
            delta = text,
            finished = finish != null,
            finishReason = finish,
            model = model
        )
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }
}
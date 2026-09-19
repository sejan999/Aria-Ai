package com.aria.ai.core.ai.adapters

import com.aria.ai.core.ai.AiHttp
import com.aria.ai.core.ai.KeyProvider
import com.aria.ai.core.ai.ModelResolver
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.aggregateViaStream
import com.aria.ai.core.ai.discovery.AnthropicModelDiscovery
import com.aria.ai.core.ai.model.ChatChunk
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.ChatResponse
import com.aria.ai.core.ai.model.Message
import com.aria.ai.core.ai.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic Claude adapter (Messages API, SSE streaming).
 *
 * Anthropic authenticates with `x-api-key`, requires `anthropic-version` and a
 * mandatory `max_tokens`; the system prompt is a top-level field rather than a
 * message. Vision uses base64 image blocks.
 *
 * The model is discovered from `/v1/models` and ranked by
 * [AnthropicModelDiscovery]; vision always resolves to the vision task so a
 * multimodal Claude is chosen.
 */
@Singleton
class AnthropicAdapter @Inject constructor(
    private val client: OkHttpClient,
    private val keys: KeyProvider,
    resolver: ModelResolver,
    selector: AnthropicModelDiscovery
) : AutoModelAdapter(resolver, selector) {

    override val id: String = ProviderIds.ANTHROPIC
    override val displayName: String = "Anthropic Claude"
    override val supportsVision: Boolean = true

    override fun apiKeyOrNull(): String? = keys.getKey(id)

    override suspend fun chat(messages: List<Message>, options: ChatOptions): ChatResponse =
        aggregateViaStream(this, messages, options)

    override fun streamChat(messages: List<Message>, options: ChatOptions): Flow<ChatChunk> = flow {
        val apiKey = keys.requireKey(id, displayName)
        val task = if (messages.any { !it.imageBase64.isNullOrBlank() }) {
            com.aria.ai.core.ai.model.TaskType.VISION
        } else {
            com.aria.ai.core.ai.model.TaskType.CHAT
        }
        val model = options.model?.takeIf { it.isNotBlank() } ?: resolveModel(task)
        val headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to ANTHROPIC_VERSION,
            "Content-Type" to "application/json"
        ) + options.extraHeaders
        val payload = buildPayload(messages, options, model)
        emitAll(AiHttp.streamSse(client, ENDPOINT, headers, payload) { data ->
            parseChunk(data, model)
        })
    }

    private fun buildPayload(messages: List<Message>, options: ChatOptions, model: String): String {
        val wire = JSONArray()
        for (message in messages) {
            if (message.role == Role.SYSTEM) continue
            wire.put(
                JSONObject()
                    .put("role", if (message.role == Role.ASSISTANT) "assistant" else "user")
                    .put("content", contentFor(message))
            )
        }

        val root = JSONObject()
            .put("model", model)
            .put("messages", wire)
            .put("max_tokens", options.maxTokens)
            .put("temperature", options.temperature.toDouble())
            .put("stream", true)

        val system = options.systemPrompt
            ?: messages.firstOrNull { it.role == Role.SYSTEM }?.content
        if (!system.isNullOrBlank()) root.put("system", system)

        return root.toString()
    }

    private fun contentFor(message: Message): Any {
        val image = message.imageBase64
        if (image.isNullOrBlank()) return message.content
        val blocks = JSONArray()
        if (message.content.isNotBlank()) {
            blocks.put(JSONObject().put("type", "text").put("text", message.content))
        }
        blocks.put(
            JSONObject()
                .put("type", "image")
                .put(
                    "source",
                    JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/jpeg")
                        .put("data", image)
                )
        )
        return blocks
    }

    private fun parseChunk(data: String, model: String): ChatChunk {
        val json = JSONObject(data)
        return when (json.optString("type", "")) {
            "content_block_delta" -> ChatChunk(
                delta = json.optJSONObject("delta")?.optString("text", "").orEmpty(),
                model = model
            )

            "message_delta" -> {
                val stopReason = json.optJSONObject("delta")?.let { delta ->
                    if (delta.isNull("stop_reason")) null else delta.optString("stop_reason", "")
                }?.takeIf { it.isNotBlank() && it != "null" }
                ChatChunk(finished = stopReason != null, finishReason = stopReason ?: "stop", model = model)
            }

            "message_stop" -> ChatChunk(finished = true, finishReason = "stop", model = model)

            "error" -> ChatChunk(
                finished = true,
                error = json.optJSONObject("error")?.optString("message", "Anthropic stream error")
                    ?: "Anthropic stream error",
                model = model
            )

            else -> ChatChunk(delta = "", model = model)
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
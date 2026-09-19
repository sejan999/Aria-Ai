package com.aria.ai.core.ai.adapters

import com.aria.ai.core.ai.AiHttp
import com.aria.ai.core.ai.KeyProvider
import com.aria.ai.core.ai.ModelResolver
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.aggregateViaStream
import com.aria.ai.core.ai.discovery.OpenAIModelDiscovery
import com.aria.ai.core.ai.model.ChatChunk
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.ChatResponse
import com.aria.ai.core.ai.model.Message
import com.aria.ai.core.ai.model.TaskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI Chat Completions adapter (`/v1/chat/completions`) with SSE streaming and
 * vision support through base64 data-uris.
 *
 * The model is discovered from `/v1/models` and ranked by
 * [OpenAIModelDiscovery]: [TaskType.CHAT] for text, [TaskType.VISION] whenever a
 * message carries an image. No model id is hardcoded here.
 */
@Singleton
class OpenAIAdapter @Inject constructor(
    private val client: OkHttpClient,
    private val keys: KeyProvider,
    resolver: ModelResolver,
    selector: OpenAIModelDiscovery
) : AutoModelAdapter(resolver, selector) {

    override val id: String = ProviderIds.OPENAI
    override val displayName: String = "OpenAI"
    override val supportsVision: Boolean = true

    override fun apiKeyOrNull(): String? = keys.getKey(id)

    override suspend fun chat(messages: List<Message>, options: ChatOptions): ChatResponse =
        aggregateViaStream(this, messages, options)

    override fun streamChat(messages: List<Message>, options: ChatOptions): Flow<ChatChunk> = flow {
        val apiKey = keys.requireKey(id, displayName)
        val task = if (messages.any { !it.imageBase64.isNullOrBlank() }) {
            TaskType.VISION
        } else {
            TaskType.CHAT
        }
        val model = options.model?.takeIf { it.isNotBlank() } ?: resolveModel(task)
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        ) + options.extraHeaders
        val payload = OpenAiWire.payload(messages, options, model)
        emitAll(AiHttp.streamSse(client, ENDPOINT, headers, payload) { data ->
            OpenAiWire.parse(data, model)
        })
    }

    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    }
}
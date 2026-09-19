package com.aria.ai.core.ai.adapters

import com.aria.ai.core.ai.AiHttp
import com.aria.ai.core.ai.KeyProvider
import com.aria.ai.core.ai.ModelResolver
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.aggregateViaStream
import com.aria.ai.core.ai.discovery.OpenRouterModelDiscovery
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
 * OpenRouter adapter — one key, hundreds of models. Aria identifies itself with
 * the optional HTTP-Referer / X-Title headers so usage is attributable.
 *
 * [OpenRouterModelDiscovery] parses the catalogue's pricing and modality data,
 * so free models are preferred for chat and image-capable models for vision.
 */
@Singleton
class OpenRouterAdapter @Inject constructor(
    private val client: OkHttpClient,
    private val keys: KeyProvider,
    resolver: ModelResolver,
    selector: OpenRouterModelDiscovery
) : AutoModelAdapter(resolver, selector) {

    override val id: String = ProviderIds.OPENROUTER
    override val displayName: String = "OpenRouter"
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
            "Content-Type" to "application/json",
            "HTTP-Referer" to SITE_URL,
            "X-Title" to SITE_TITLE
        ) + options.extraHeaders
        val payload = OpenAiWire.payload(messages, options, model)
        emitAll(AiHttp.streamSse(client, ENDPOINT, headers, payload) { data ->
            OpenAiWire.parse(data, model)
        })
    }

    private companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        const val SITE_URL = "https://github.com/sejan999/Aria-Ai"
        const val SITE_TITLE = "Aria Ai"
    }
}
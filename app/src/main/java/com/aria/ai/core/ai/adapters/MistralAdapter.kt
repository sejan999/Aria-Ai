package com.aria.ai.core.ai.adapters

import com.aria.ai.core.ai.AiHttp
import com.aria.ai.core.ai.KeyProvider
import com.aria.ai.core.ai.ModelResolver
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.aggregateViaStream
import com.aria.ai.core.ai.discovery.MistralModelDiscovery
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
 * Mistral AI adapter — OpenAI-compatible endpoint with the standard bearer
 * token. The model is discovered from `/v1/models` and ranked by
 * [MistralModelDiscovery]; vision turns get a Pixtral model automatically.
 */
@Singleton
class MistralAdapter @Inject constructor(
    private val client: OkHttpClient,
    private val keys: KeyProvider,
    resolver: ModelResolver,
    selector: MistralModelDiscovery
) : AutoModelAdapter(resolver, selector) {

    override val id: String = ProviderIds.MISTRAL
    override val displayName: String = "Mistral AI"
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
        const val ENDPOINT = "https://api.mistral.ai/v1/chat/completions"
    }
}
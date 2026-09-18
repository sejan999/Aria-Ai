package com.aria.ai.core.ai.adapters

import com.aria.ai.core.ai.AIProvider
import com.aria.ai.core.ai.ModelNotAvailableException
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.aggregateViaStream
import com.aria.ai.core.ai.model.ChatChunk
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.ChatResponse
import com.aria.ai.core.ai.model.Message
import com.aria.ai.core.ai.model.Role
import com.aria.ai.core.ml.OnDeviceLlm
import com.aria.ai.data.repository.ProviderSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fully offline brain: Gemma running through MediaPipe's Kotlin task API.
 *
 * No API key, no network, no native sources in this repository. The model file is
 * supplied by the user and its path lives in Settings; tokens stream back through
 * MediaPipe's progress listener and are forwarded as [ChatChunk]s.
 */
@Singleton
class OnDeviceGemmaAdapter @Inject constructor(
    private val llm: OnDeviceLlm,
    private val settings: ProviderSettingsRepository
) : AIProvider {

    override val id: String = ProviderIds.ON_DEVICE_GEMMA
    override val displayName: String = "Gemma (on-device)"
    override val defaultModel: String = "gemma-2b-it"
    override val isLocal: Boolean = true
    override val requiresKey: Boolean = false

    override suspend fun chat(messages: List<Message>, options: ChatOptions): ChatResponse =
        aggregateViaStream(this, messages, options)

    override fun streamChat(messages: List<Message>, options: ChatOptions): Flow<ChatChunk> = channelFlow {
        val modelPath = requireModelPath()
        val prompt = buildPrompt(messages, options)
        val accumulated = StringBuilder()

        val full = llm.generateStreaming(modelPath, prompt) { delta ->
            accumulated.append(delta)
            trySend(ChatChunk(delta = delta, model = defaultModel))
        }

        if (accumulated.isEmpty() && full.isNotBlank()) {
            trySend(ChatChunk(delta = full, model = defaultModel))
        }
        trySend(ChatChunk(delta = "", finished = true, finishReason = "stop", model = defaultModel))
        close()
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    override suspend fun testConnection(): Result<String> = runCatching {
        val reply = chat(listOf(Message.user("Reply with the single word: ready")), ChatOptions.PING)
        if (reply.text.isBlank()) error("On-device model returned an empty response")
        "OK · ${reply.model} · \"${reply.text.take(48)}\" (fully offline)"
    }

    // ------------------------------------------------------------------ internals

    private suspend fun requireModelPath(): String {
        val configured = settings.currentGemmaModelPath()
        if (configured.isNullOrBlank()) {
            throw ModelNotAvailableException(
                "No on-device model configured. Copy a Gemma .bin/.task file onto the device, " +
                    "then set its full path under Settings → On-device model."
            )
        }
        if (!File(configured).exists()) {
            throw ModelNotAvailableException(
                "On-device model not found at $configured. Update the path under Settings → On-device model."
            )
        }
        return configured
    }

    private fun buildPrompt(messages: List<Message>, options: ChatOptions): String = buildString {
        val system = options.systemPrompt
            ?: messages.firstOrNull { it.role == Role.SYSTEM }?.content
        if (!system.isNullOrBlank()) {
            append("System: ").append(system).append('\n')
        }
        for (message in messages) {
            if (message.role == Role.SYSTEM) continue
            if (message.content.isBlank()) continue
            val speaker = if (message.role == Role.ASSISTANT) "Assistant" else "User"
            append(speaker).append(": ").append(message.content).append('\n')
        }
        append("Assistant:")
    }
}
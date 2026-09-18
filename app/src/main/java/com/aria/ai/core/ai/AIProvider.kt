package com.aria.ai.core.ai

import com.aria.ai.core.ai.model.ChatChunk
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.ChatResponse
import com.aria.ai.core.ai.model.Message
import kotlinx.coroutines.flow.Flow

/** Canonical provider identifiers used by the vault, registry and settings. */
object ProviderIds {
    const val GEMINI = "gemini"
    const val OPENAI = "openai"
    const val GROQ = "groq"
    const val ANTHROPIC = "anthropic"
    const val MISTRAL = "mistral"
    const val OPENROUTER = "openrouter"
    const val ON_DEVICE_GEMMA = "on_device_gemma"

    /** Every provider Aria ships with, in display order. */
    val ALL: List<String> = listOf(
        GEMINI, OPENAI, GROQ, ANTHROPIC, MISTRAL, OPENROUTER, ON_DEVICE_GEMMA
    )

    /** Providers that need a cloud API key (everything except on-device Gemma). */
    val CLOUD: List<String> = listOf(GEMINI, OPENAI, GROQ, ANTHROPIC, MISTRAL, OPENROUTER)

    fun displayName(id: String): String = when (id) {
        GEMINI -> "Google Gemini"
        OPENAI -> "OpenAI"
        GROQ -> "Groq"
        ANTHROPIC -> "Anthropic Claude"
        MISTRAL -> "Mistral AI"
        OPENROUTER -> "OpenRouter"
        ON_DEVICE_GEMMA -> "Gemma (on-device)"
        else -> id
    }
}

/**
 * One unified contract for every brain Aria can talk to — cloud or on-device.
 *
 * Implementations must:
 *  1. fetch their key through [KeyProvider] at call time (never cache it);
 *  2. throw [MissingApiKeyException] when the key is absent;
 *  3. never log key material ([KeyRedactor] is applied to every error);
 *  4. emit incremental [ChatChunk]s from [streamChat] and end with a chunk whose
 *     `finished` flag is true.
 */
interface AIProvider {

    val id: String
    val displayName: String
    val defaultModel: String

    /** True when this adapter can consume [Message.imageBase64] payloads. */
    val supportsVision: Boolean get() = false

    /** True for adapters that run entirely on this device. */
    val isLocal: Boolean get() = false

    /** True when a stored API key is required before the adapter can run. */
    val requiresKey: Boolean get() = true

    suspend fun chat(messages: List<Message>, options: ChatOptions = ChatOptions()): ChatResponse

    fun streamChat(messages: List<Message>, options: ChatOptions = ChatOptions()): Flow<ChatChunk>

    /**
     * Round-trips a tiny prompt to prove the credentials work. Never throws:
     * failures are returned as [Result.failure] with a redacted message.
     */
    suspend fun testConnection(): Result<String> = runCatching {
        val probe = chat(
            listOf(Message.user("Reply with the single word: ready")),
            ChatOptions.PING
        )
        val text = probe.text.trim().ifBlank {
            throw IllegalStateException("Provider returned an empty response")
        }
        "OK · ${probe.model ?: defaultModel} · \"${text.take(48)}\""
    }
}
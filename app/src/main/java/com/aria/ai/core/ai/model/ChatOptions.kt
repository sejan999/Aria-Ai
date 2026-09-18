package com.aria.ai.core.ai.model

/**
 * Per-call generation settings. Every adapter maps these onto its own wire
 * format (Gemini generationConfig, OpenAI-compatible JSON, Anthropic body…).
 */
data class ChatOptions(
    val model: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val topP: Float = 0.95f,
    val systemPrompt: String? = null,
    val stream: Boolean = true,
    val extraHeaders: Map<String, String> = emptyMap()
) {
    fun withModel(model: String?): ChatOptions = copy(model = model)

    fun withSystem(prompt: String?): ChatOptions = copy(systemPrompt = prompt)

    companion object {
        /** Low-cost, deterministic options used by "Test connection". */
        val PING: ChatOptions = ChatOptions(
            temperature = 0f,
            maxTokens = 24,
            topP = 1f,
            stream = true
        )
    }
}
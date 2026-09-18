package com.aria.ai.core.ai.model

/**
 * Result of a complete (non-streaming) chat call.
 */
data class ChatResponse(
    val text: String,
    val model: String? = null,
    val finishReason: String? = null,
    val usageTokens: Int? = null,
    val providerId: String? = null,
    val fromOnDevice: Boolean = false
) {
    val isEmpty: Boolean get() = text.isBlank()
}
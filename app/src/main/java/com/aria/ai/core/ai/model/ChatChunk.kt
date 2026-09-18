package com.aria.ai.core.ai.model

/**
 * Incremental piece of a streaming completion.
 *
 * [error] is a redacted, user-safe description when the provider stream reports
 * a failure mid-flight (never contains API keys — see KeyRedactor).
 */
data class ChatChunk(
    val delta: String = "",
    val finished: Boolean = false,
    val finishReason: String? = null,
    val model: String? = null,
    val error: String? = null
) {
    val hasText: Boolean get() = delta.isNotEmpty()

    companion object {
        fun text(delta: String): ChatChunk = ChatChunk(delta = delta)
        fun done(reason: String? = "stop"): ChatChunk =
            ChatChunk(finished = true, finishReason = reason)
        fun failure(message: String): ChatChunk =
            ChatChunk(finished = true, error = message)
    }
}
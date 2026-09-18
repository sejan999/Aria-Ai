package com.aria.ai.core.ai.model

/**
 * Speaker of a single conversational turn.
 *
 * [wire] is the exact lowercase token expected by the OpenAI/Anthropic style
 * APIs; Gemini maps ASSISTANT to "model" internally inside its adapter.
 */
enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromWire(value: String): Role =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: USER
    }
}

/**
 * One provider-agnostic chat message.
 *
 * [imageBase64] carries an optional JPEG (no data-uri prefix) for vision models —
 * used by the VisionAgent to describe the current screen.
 */
data class Message(
    val role: Role,
    val content: String,
    val name: String? = null,
    val imageBase64: String? = null
) {
    val isVision: Boolean get() = !imageBase64.isNullOrBlank()

    companion object {
        fun system(text: String): Message = Message(Role.SYSTEM, text)
        fun user(text: String): Message = Message(Role.USER, text)
        fun assistant(text: String): Message = Message(Role.ASSISTANT, text)
        fun vision(text: String, imageBase64: String): Message =
            Message(Role.USER, text, imageBase64 = imageBase64)
    }
}
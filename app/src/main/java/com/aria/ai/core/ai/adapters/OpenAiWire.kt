package com.aria.ai.core.ai.adapters

import com.aria.ai.core.ai.model.ChatChunk
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.Message
import com.aria.ai.core.ai.model.Role
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire format shared by every OpenAI-compatible endpoint (OpenAI, Groq, Mistral,
 * OpenRouter). Kept in one place so a fix lands for all four providers at once.
 */
internal object OpenAiWire {

    fun payload(messages: List<Message>, options: ChatOptions, model: String): String {
        val wire = JSONArray()

        val system = options.systemPrompt
            ?: messages.firstOrNull { it.role == Role.SYSTEM }?.content
        if (!system.isNullOrBlank()) {
            wire.put(JSONObject().put("role", "system").put("content", system))
        }

        for (message in messages) {
            if (message.role == Role.SYSTEM) continue
            wire.put(
                JSONObject()
                    .put("role", if (message.role == Role.ASSISTANT) "assistant" else "user")
                    .put("content", contentFor(message))
            )
        }

        return JSONObject()
            .put("model", model)
            .put("messages", wire)
            .put("temperature", options.temperature.toDouble())
            .put("max_tokens", options.maxTokens)
            .put("top_p", options.topP.toDouble())
            .put("stream", true)
            .toString()
    }

    private fun contentFor(message: Message): Any {
        val image = message.imageBase64
        if (image.isNullOrBlank()) return message.content
        val parts = JSONArray()
        if (message.content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", message.content))
        }
        parts.put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image"))
        )
        return parts
    }

    fun parse(data: String, model: String): ChatChunk {
        val json = JSONObject(data)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: return ChatChunk(delta = "", model = model)

        val delta = choice.optJSONObject("delta")
        val text = when {
            delta == null -> ""
            delta.isNull("content") -> ""
            else -> delta.optString("content", "").let { if (it == "null") "" else it }
        }

        val rawFinish = if (choice.isNull("finish_reason")) "" else choice.optString("finish_reason", "")
        val finish = rawFinish.takeIf { it.isNotBlank() && it != "null" }

        return ChatChunk(
            delta = text,
            finished = finish != null,
            finishReason = finish,
            model = json.optString("model", model)
        )
    }
}
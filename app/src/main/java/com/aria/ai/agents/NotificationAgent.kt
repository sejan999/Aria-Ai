package com.aria.ai.agents

import com.aria.ai.core.ai.ProviderRegistry
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.Message
import com.aria.ai.core.system.NotificationReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification specialist: reads the rolling in-memory buffer maintained by
 * [NotificationReader] and either lists it or asks the active provider for a
 * short digest. Nothing is sent to a provider unless the user asks for a summary.
 */
@Singleton
class NotificationAgent @Inject constructor(
    private val registry: ProviderRegistry
) : AriaAgent {

    override val id: String = "notifications"
    override val name: String = "Notification Agent"
    override val description: String = "Reads and summarises recent notifications"
    override val keywords: List<String> = listOf(
        "notification", "notifications", "did i miss", "what did i miss",
        "unread", "who messaged", "what's new", "whats new"
    )

    override suspend fun handle(intent: String, utterance: String): AgentResult {
        if (!NotificationReader.connected.value) {
            return AgentResult(
                id,
                false,
                "Notification access is off. Enable Aria under Settings → Notifications → Device & app " +
                    "notifications, then ask me again."
            )
        }

        val items = NotificationReader.recent(12)
        if (items.isEmpty()) {
            return AgentResult(id, true, "Nothing new — your notification list is empty.")
        }

        val digest = items.joinToString(separator = "\n") { "• ${it.summary()}" }

        val wantsDigest = intent.contains("summar") ||
            utterance.lowercase().contains("summar") ||
            utterance.lowercase().contains("brief")

        if (!wantsDigest) {
            return AgentResult(id, true, "You have ${items.size} recent notifications:\n$digest")
        }

        val summary = runCatching {
            registry.chat(
                listOf(
                    Message.system(
                        "You are Aria's notification analyst. Summarise the notifications in at most " +
                            "three short sentences. Be concrete about senders and anything that needs a " +
                            "reply. Never invent details that are not in the list."
                    ),
                    Message.user(digest)
                ),
                ChatOptions(maxTokens = 260, temperature = 0.3f)
            ).text.trim()
        }.getOrNull()

        return AgentResult(id, true, summary?.takeIf { it.isNotBlank() } ?: digest)
    }
}
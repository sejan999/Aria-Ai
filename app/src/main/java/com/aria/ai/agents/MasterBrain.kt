package com.aria.ai.agents

import com.aria.ai.core.ai.MissingApiKeyException
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.ProviderRegistry
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.Message
import com.aria.ai.core.ai.model.Role
import com.aria.ai.core.tts.TtsEngine
import com.aria.ai.data.repository.ConversationRepository
import com.aria.ai.data.repository.ProviderSettingsRepository
import com.aria.ai.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a sub-agent handling a request. */
data class AgentResult(
    val agentId: String,
    val success: Boolean,
    val text: String
)

/** Contract for every specialist Aria can delegate to. */
interface AriaAgent {
    val id: String
    val name: String
    val description: String

    /** Lower-case phrases that route an utterance to this agent. */
    val keywords: List<String>

    suspend fun handle(intent: String, utterance: String): AgentResult
}

/**
 * The orchestrator.
 *
 * Every turn follows the same path: persist the user's words → try to route the
 * request to a specialist agent (deterministic, no model round-trip) → otherwise
 * fall back to the active LLM provider → persist Aria's reply → speak it when
 * auto-speak is enabled.
 */
@Singleton
class MasterBrain @Inject constructor(
    private val registry: ProviderRegistry,
    private val settings: ProviderSettingsRepository,
    private val conversations: ConversationRepository,
    private val tts: TtsEngine,
    private val agents: Map<String, @JvmSuppressWildcards AriaAgent>,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _lastReply = MutableStateFlow<String?>(null)
    val lastReply: StateFlow<String?> = _lastReply.asStateFlow()

    /** Names of every registered specialist (shown in Settings). */
    val agentNames: List<String> get() = agents.values.map { it.name }

    /** Fire-and-forget entry point used by the UI. */
    fun submit(utterance: String) {
        if (utterance.isBlank()) return
        scope.launch { respond(utterance) }
    }

    /** Full turn: record → route → answer → persist → speak. */
    suspend fun respond(utterance: String): String {
        _busy.value = true
        val reply = try {
            conversations.recordUserTurn(utterance)
            answer(utterance)
        } catch (t: Throwable) {
            "Something went wrong: ${t.message?.take(160) ?: t.javaClass.simpleName}"
        } finally {
            _busy.value = false
        }

        _lastReply.value = reply
        if (settings.currentAutoSpeak()) {
            tts.speak(reply)
        }
        return reply
    }

    private suspend fun answer(utterance: String): String {
        val routed = route(utterance)
        if (routed != null) {
            val (agent, keyword) = routed
            val result = agent.handle(keyword, utterance)
            conversations.recordAssistantTurn(result.text, agentId = agent.id)
            return result.text
        }
        return cloudAnswer(utterance)
    }

    /** Keyword routing table: the first agent claiming a phrase wins. */
    fun route(utterance: String): Pair<AriaAgent, String>? {
        val heard = utterance.lowercase()
        for (agent in agents.values) {
            val hit = agent.keywords.firstOrNull { heard.contains(it) }
            if (hit != null) return agent to hit
        }
        return null
    }

/** Fallback path: the active provider answers with conversation context. */
    private suspend fun cloudAnswer(utterance: String): String {
        val provider = registry.active()
        val history = conversations.recentTurns(limit = 10)

        val messages = buildList {
            add(Message.system(SYSTEM_PROMPT))
            for (turn in history) {
                if (turn.text.isBlank()) continue
                if (turn.fromUser) {
                    add(Message(Role.USER, turn.text))
                } else {
                    add(Message(Role.ASSISTANT, turn.text))
                }
            }
            add(Message.user(utterance))
        }

        return try {
            val response = provider.chat(messages, ChatOptions())
            val text = response.text.ifBlank {
                "I did not get a usable answer from ${provider.displayName}."
            }
            conversations.recordAssistantTurn(text, providerId = provider.id)
            text
        } catch (missing: MissingApiKeyException) {
            val message = missing.message
                ?: "No API key configured for ${ProviderIds.displayName(missing.providerId)}."
            conversations.recordAssistantTurn(message, providerId = provider.id)
            message
        } catch (t: Throwable) {
            val message = "${provider.displayName} could not answer: " +
                (t.message?.take(180) ?: t.javaClass.simpleName)
            conversations.recordAssistantTurn(message, providerId = provider.id)
            message
        }
    }

    /** One-shot completion on the active provider, for agents that need a model. */
    suspend fun ask(messages: List<Message>, options: ChatOptions = ChatOptions()) =
        registry.chat(messages, options)

    private companion object {
        val SYSTEM_PROMPT = """
            You are Aria Ai — a voice-first execution assistant living on the user's Android phone.
            Rules:
            • Answer in at most three short sentences unless detail is requested: replies are read aloud.
            • Be concrete and action-oriented.
            • You run on-device: you can open apps, control the flashlight, volume and brightness,
              read notifications, describe the screen and take calls when asked.
            • Never invent personal data, contacts or messages you were not given.
        """.trimIndent()
    }
}
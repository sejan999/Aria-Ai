package com.aria.ai.data.repository

import com.aria.ai.data.local.ConversationDao
import com.aria.ai.data.local.ConversationEntity
import com.aria.ai.data.local.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** One transcript line rendered by the Home screen. */
data class ChatLine(
    val role: String,
    val text: String,
    val timestamp: Long
) {
    val fromUser: Boolean get() = role.equals("user", ignoreCase = true)
}

/**
 * Conversation memory for Aria: every spoken/typed turn is persisted locally so
 * the assistant keeps context across restarts, and the Home screen can render the
 * running transcript reactively.
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val dao: ConversationDao
) {

    private val lock = Any()

    @Volatile
    private var activeConversationId: Long? = null

    private val _currentTitle = MutableStateFlow(DEFAULT_TITLE)
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    /** Live transcript of the newest conversation. */
    val transcript: Flow<List<ChatLine>> = dao.observeLatestMessages().map { messages ->
        messages.map { ChatLine(role = it.role, text = it.content, timestamp = it.timestamp) }
    }

    /** Returns the active conversation id, creating one on first use. */
    suspend fun ensureConversation(): Long {
        activeConversationId?.let { return it }
        val existing = dao.latestConversation()
        val id = existing?.id ?: run {
            val created = dao.insertConversation(
                ConversationEntity(
                    title = DEFAULT_TITLE,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            _currentTitle.value = DEFAULT_TITLE
            created
        }
        synchronized(lock) { activeConversationId = id }
        if (existing != null) _currentTitle.value = existing.title
        return id
    }

    /** Begins a fresh conversation (used by "new session" in the UI). */
    suspend fun startNewConversation(title: String = DEFAULT_TITLE): Long {
        val id = dao.insertConversation(ConversationEntity(title = title))
        synchronized(lock) { activeConversationId = id }
        _currentTitle.value = title
        return id
    }

    suspend fun recordUserTurn(text: String) {
        val conversationId = ensureConversation()
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "user",
                content = text.take(MAX_TURN_CHARS)
            )
        )
        dao.touch(conversationId, System.currentTimeMillis())
        maybeRetitle(conversationId, text)
    }

    suspend fun recordAssistantTurn(
        text: String,
        providerId: String? = null,
        agentId: String? = null
    ) {
        val conversationId = ensureConversation()
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = text.take(MAX_TURN_CHARS),
                providerId = providerId,
                agentId = agentId
            )
        )
        dao.touch(conversationId, System.currentTimeMillis())
    }

    /** Newest [limit] turns, oldest-first, for prompt building. */
    suspend fun recentTurns(limit: Int = 12): List<ChatLine> =
        dao.latestMessagesDesc(limit)
            .reversed()
            .map { ChatLine(role = it.role, text = it.content, timestamp = it.timestamp) }

    suspend fun history(): List<MessageEntity> {
        val conversationId = ensureConversation()
        return dao.messagesOf(conversationId)
    }

    suspend fun messageCount(): Int = dao.messageCount()

    suspend fun clearAll() {
        dao.clearConversations()
        synchronized(lock) { activeConversationId = null }
        _currentTitle.value = DEFAULT_TITLE
    }

    private suspend fun maybeRetitle(conversationId: Long, firstUserTurn: String) {
        if (dao.messageCount() > 2) return
        val title = firstUserTurn.trim().take(48).ifBlank { DEFAULT_TITLE }
        dao.rename(conversationId, title)
        _currentTitle.value = title
    }

    private companion object {
        const val DEFAULT_TITLE = "Aria session"
        const val MAX_TURN_CHARS = 4_000
    }
}
package com.aria.ai.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import kotlinx.coroutines.flow.Flow

/** One Aria conversation (a rolling transcript of turns). */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** One stored turn, from either the user or Aria. */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    val role: String,
    val content: String,
    val providerId: String? = null,
    val agentId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/** Conversation plus its ordered turns (used by the transcript screen). */
data class ConversationWithMessages(
    @Embedded val conversation: ConversationEntity,
    @Relation(parentColumn = "id", entityColumn = "conversationId")
    val messages: List<MessageEntity>
)

@Dao
interface ConversationDao {

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestConversation(): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun messagesOf(conversationId: Long): List<MessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE conversationId = " +
            "(SELECT id FROM conversations ORDER BY updatedAt DESC LIMIT 1) ORDER BY timestamp ASC"
    )
    fun observeLatestMessages(): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM chat_messages WHERE conversationId = " +
            "(SELECT id FROM conversations ORDER BY updatedAt DESC LIMIT 1) " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun latestMessagesDesc(limit: Int): List<MessageEntity>

    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :conversationId")
    suspend fun touch(conversationId: Long, timestamp: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :conversationId")
    suspend fun rename(conversationId: Long, title: String)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: Long)

    @Query("DELETE FROM conversations")
    suspend fun clearConversations()

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun messageCount(): Int
}
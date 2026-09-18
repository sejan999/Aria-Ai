package com.aria.ai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Aria Ai local store: conversation history and automation rules.
 *
 * API keys are NOT here — they live in the hardware-backed EncryptedSharedPreferences
 * vault (see `data/vault/ApiKeyVault`), never in the Room database.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AutomationEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AriaDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun automationDao(): AutomationDao

    companion object {
        const val NAME = "aria.db"
    }
}
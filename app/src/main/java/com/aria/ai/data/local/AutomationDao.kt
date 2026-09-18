package com.aria.ai.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "triggerPhrase") val trigger: String,
    val actionType: String,
    val actionPayload: String = "{}",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun describe(): String =
        if (enabled) "\"$trigger\" → $actionType" else "\"$trigger\" → $actionType (paused)"
}

@Dao
interface AutomationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AutomationEntity): Long

    @Query("SELECT * FROM automations ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun enabled(): List<AutomationEntity>

    @Query("SELECT * FROM automations ORDER BY createdAt DESC")
    suspend fun all(): List<AutomationEntity>

    @Query("UPDATE automations SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM automations WHERE triggerPhrase = :trigger")
    suspend fun deleteByTrigger(trigger: String): Int

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM automations")
    suspend fun count(): Int

    @Query("DELETE FROM automations")
    suspend fun clear()
}
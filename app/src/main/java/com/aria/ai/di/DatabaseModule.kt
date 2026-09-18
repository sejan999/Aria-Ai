package com.aria.ai.di

import android.content.Context
import androidx.room.Room
import com.aria.ai.data.local.AriaDatabase
import com.aria.ai.data.local.AutomationDao
import com.aria.ai.data.local.ConversationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Room database + DAO provisioning. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAriaDatabase(@ApplicationContext context: Context): AriaDatabase =
        Room.databaseBuilder(context, AriaDatabase::class.java, AriaDatabase.NAME)
            // Schema version 1 — destructive migration keeps alpha devices usable
            // while the conversation/automation tables are still evolving.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(database: AriaDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideAutomationDao(database: AriaDatabase): AutomationDao = database.automationDao()
}
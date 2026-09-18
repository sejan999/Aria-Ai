package com.aria.ai.di

import android.content.Context
import com.aria.ai.core.audio.ListenerWakeWordDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the long-lived application-wide coroutine scope. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Application-level wiring that has nothing to do with I/O: the shared coroutine
 * scope every singleton (agents, bridges, overlay) launches background work in.
 */
@Module
@InstallIn(SingletonComponent::class)
object AriaAppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Always-listening wake-word detector (TFLite phrase spotter from
     * `core.audio`). This is distinct from the energy-gate detector in
     * `core.ml` used by [AudioBridge]; both are singletons with different APIs.
     */
    @Provides
    @Singleton
    fun provideListenerWakeWordDetector(
        @ApplicationContext context: Context
    ): ListenerWakeWordDetector = ListenerWakeWordDetector(context)
}
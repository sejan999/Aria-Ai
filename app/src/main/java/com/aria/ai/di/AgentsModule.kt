package com.aria.ai.di

import com.aria.ai.agents.AriaAgent
import com.aria.ai.agents.AutomationAgent
import com.aria.ai.agents.CallScreenAgent
import com.aria.ai.agents.HardwareAgent
import com.aria.ai.agents.NotificationAgent
import com.aria.ai.agents.SystemAppAgent
import com.aria.ai.agents.VisionAgent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Binds the specialists into a `Map<String, AriaAgent>` that MasterBrain routes
 * over. Routing is deterministic keyword matching, so a new agent only needs a
 * binding here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentsModule {

    @Binds
    @IntoMap
    @StringKey("notifications")
    abstract fun bindNotificationAgent(impl: NotificationAgent): AriaAgent

    @Binds
    @IntoMap
    @StringKey("call_screen")
    abstract fun bindCallScreenAgent(impl: CallScreenAgent): AriaAgent

    @Binds
    @IntoMap
    @StringKey("system_apps")
    abstract fun bindSystemAppAgent(impl: SystemAppAgent): AriaAgent

    @Binds
    @IntoMap
    @StringKey("hardware")
    abstract fun bindHardwareAgent(impl: HardwareAgent): AriaAgent

    @Binds
    @IntoMap
    @StringKey("vision")
    abstract fun bindVisionAgent(impl: VisionAgent): AriaAgent

    @Binds
    @IntoMap
    @StringKey("automation")
    abstract fun bindAutomationAgent(impl: AutomationAgent): AriaAgent
}
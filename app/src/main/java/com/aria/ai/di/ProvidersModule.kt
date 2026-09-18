package com.aria.ai.di

import com.aria.ai.core.ai.AIProvider
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.adapters.AnthropicAdapter
import com.aria.ai.core.ai.adapters.GeminiAdapter
import com.aria.ai.core.ai.adapters.GroqAdapter
import com.aria.ai.core.ai.adapters.MistralAdapter
import com.aria.ai.core.ai.adapters.OnDeviceGemmaAdapter
import com.aria.ai.core.ai.adapters.OpenAIAdapter
import com.aria.ai.core.ai.adapters.OpenRouterAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Binds every adapter into a `Map<String, AIProvider>` keyed by provider id.
 *
 * ProviderRegistry consumes that map, so adding a provider is a single method
 * here plus its adapter — no existing code changes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProvidersModule {

    @Binds
    @IntoMap
    @StringKey(ProviderIds.GEMINI)
    abstract fun bindGeminiAdapter(impl: GeminiAdapter): AIProvider

    @Binds
    @IntoMap
    @StringKey(ProviderIds.OPENAI)
    abstract fun bindOpenAiAdapter(impl: OpenAIAdapter): AIProvider

    @Binds
    @IntoMap
    @StringKey(ProviderIds.GROQ)
    abstract fun bindGroqAdapter(impl: GroqAdapter): AIProvider

    @Binds
    @IntoMap
    @StringKey(ProviderIds.ANTHROPIC)
    abstract fun bindAnthropicAdapter(impl: AnthropicAdapter): AIProvider

    @Binds
    @IntoMap
    @StringKey(ProviderIds.MISTRAL)
    abstract fun bindMistralAdapter(impl: MistralAdapter): AIProvider

    @Binds
    @IntoMap
    @StringKey(ProviderIds.OPENROUTER)
    abstract fun bindOpenRouterAdapter(impl: OpenRouterAdapter): AIProvider

    @Binds
    @IntoMap
    @StringKey(ProviderIds.ON_DEVICE_GEMMA)
    abstract fun bindOnDeviceGemmaAdapter(impl: OnDeviceGemmaAdapter): AIProvider
}
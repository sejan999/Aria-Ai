package com.aria.ai.di

import com.aria.ai.core.ai.AutoModelSelector
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.discovery.AnthropicModelDiscovery
import com.aria.ai.core.ai.discovery.GeminiModelDiscovery
import com.aria.ai.core.ai.discovery.GroqModelDiscovery
import com.aria.ai.core.ai.discovery.MistralModelDiscovery
import com.aria.ai.core.ai.discovery.OnDeviceGemmaSelector
import com.aria.ai.core.ai.discovery.OpenAIModelDiscovery
import com.aria.ai.core.ai.discovery.OpenRouterModelDiscovery
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Binds every [AutoModelSelector] into a `Map<String, AutoModelSelector>` keyed
 * by provider id — the same convention `ProvidersModule` uses for adapters.
 *
 * The map is what `ProviderRegistry.selectorFor()` serves, so adding a provider
 * means one `@Binds` here plus its discovery class, with no change to the
 * registry, the adapters or the UI.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoveryModule {

    @Binds
    @IntoMap
    @StringKey(ProviderIds.GEMINI)
    abstract fun bindGeminiDiscovery(impl: GeminiModelDiscovery): AutoModelSelector

    @Binds
    @IntoMap
    @StringKey(ProviderIds.OPENAI)
    abstract fun bindOpenAiDiscovery(impl: OpenAIModelDiscovery): AutoModelSelector

    @Binds
    @IntoMap
    @StringKey(ProviderIds.GROQ)
    abstract fun bindGroqDiscovery(impl: GroqModelDiscovery): AutoModelSelector

    @Binds
    @IntoMap
    @StringKey(ProviderIds.ANTHROPIC)
    abstract fun bindAnthropicDiscovery(impl: AnthropicModelDiscovery): AutoModelSelector

    @Binds
    @IntoMap
    @StringKey(ProviderIds.MISTRAL)
    abstract fun bindMistralDiscovery(impl: MistralModelDiscovery): AutoModelSelector

    @Binds
    @IntoMap
    @StringKey(ProviderIds.OPENROUTER)
    abstract fun bindOpenRouterDiscovery(impl: OpenRouterModelDiscovery): AutoModelSelector

    @Binds
    @IntoMap
    @StringKey(ProviderIds.ON_DEVICE_GEMMA)
    abstract fun bindOnDeviceGemmaSelector(impl: OnDeviceGemmaSelector): AutoModelSelector
}
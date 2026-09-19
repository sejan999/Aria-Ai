package com.aria.ai.core.ai

import com.aria.ai.core.ai.model.ChatChunk
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.ChatResponse
import com.aria.ai.core.ai.model.ModelInfo
import com.aria.ai.core.ai.model.Message
import com.aria.ai.core.ai.model.TaskType
import com.aria.ai.data.repository.ProviderSettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only snapshot of one provider for the Settings UI.
 *
 * It deliberately carries [fingerprint] (a redacted preview from the vault)
 * instead of any key material, so a [ProviderStatus] is always safe to render,
 * log or keep in a ViewModel.
 */
data class ProviderStatus(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val activeModel: String,
    val hasKey: Boolean,
    val requiresKey: Boolean,
    val isActive: Boolean,
    val isLocal: Boolean,
    val supportsVision: Boolean,
    val fingerprint: String,
    val modelOverride: String? = null,
    /** Auto-selected model for CHAT, or null while undiscovered. */
    val chatModel: String? = null,
    /** Auto-selected model for VISION, or null when the provider has none. */
    val visionModel: String? = null,
    /** Auto-selected model for realtime AUDIO; null when unsupported. */
    val audioModel: String? = null,
    /** How many models the provider's ListModels endpoint returned. */
    val catalogueSize: Int = 0,
    /** Age of the cached catalogue in ms, or null when nothing is cached. */
    val cacheAgeMillis: Long? = null
) {
    /** True when the provider is usable right now. */
    val ready: Boolean get() = !requiresKey || hasKey

    /** True once discovery has produced a chat model. */
    val catalogueLoaded: Boolean get() = chatModel != null
}

/**
 * The switchboard between Aria's agents and its brains.
 *
 * Adapters are injected as a `Map<String, AIProvider>` (see `di/ProvidersModule`),
 * so adding a provider never touches this class. Every cloud call resolves the
 * key through the adapter → [KeyProvider] → encrypted vault chain, which means a
 * key rotated in Settings applies to the very next request.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val providers: Map<String, @JvmSuppressWildcards AIProvider>,
    private val settings: ProviderSettingsRepository,
    private val keys: KeyProvider,
    private val discoveries: Map<String, @JvmSuppressWildcards AutoModelSelector>,
    private val resolver: ModelResolver
) {

    /**
     * Automatic-model API for one provider. Null only when the provider has no
     * registered discovery implementation.
     */
    fun selectorFor(providerId: String): AutoModelSelector? = discoveries[providerId]

    /**
     * Resolves the model Aria will use for [task] on the active provider,
     * discovering and caching the catalogue as needed. Returns null when the
     * provider cannot serve the task (e.g. realtime audio on Anthropic).
     */
    suspend fun activeModelFor(task: TaskType): String? {
        val provider = active()
        val selector = selectorFor(provider.id) ?: return null
        val apiKey = if (provider.requiresKey) keys.getKey(provider.id) else ""
        return resolver.resolveOrNull(selector, provider.id, apiKey, task)
    }

    /** Resolves the model for [task] on a specific provider, or null. */
    suspend fun modelFor(providerId: String, task: TaskType): String? {
        val provider = byId(providerId) ?: return null
        val selector = selectorFor(providerId) ?: return null
        val apiKey = if (provider.requiresKey) keys.getKey(providerId) else ""
        return resolver.resolveOrNull(selector, providerId, apiKey, task)
    }

    /**
     * Drops the cached catalogue and every cached selection for one provider so
     * the next call re-discovers. Backs the UI "Refresh Models" action.
     */
    fun refreshModels(providerId: String) = resolver.invalidate(providerId)

    /** Drops every cached catalogue (all providers). */
    fun refreshAllModels() = resolver.invalidateAll()

    /** Full catalogue for a provider, or null when no key/selector is available. */
    suspend fun availableModels(providerId: String): List<ModelInfo>? {
        val provider = byId(providerId) ?: return null
        val selector = selectorFor(providerId) ?: return null
        val apiKey = if (provider.requiresKey) keys.getKey(providerId) else ""
        if (provider.requiresKey && apiKey.isNullOrBlank()) return null
        return runCatching {
            resolver.catalogue(selector, providerId, apiKey)
        }.getOrNull()
    }

    /**
     * The chain Aria walks when the active provider cannot answer: every other
     * keyed cloud provider, then the always-available on-device Gemma brain.
     */
    fun fallbackChain(): List<AIProvider> {
        val activeId = activeId()
        val keyed = ordered().filter { provider ->
            provider.id != activeId && (!provider.requiresKey || keys.hasKey(provider.id))
        }
        val local = byId(ProviderIds.ON_DEVICE_GEMMA)
        return if (local != null && local.id != activeId) keyed + local else keyed
    }

    /** Every registered provider, in display order. */
    fun ordered(): List<AIProvider> = ProviderIds.ALL.mapNotNull { providers[it] }

    fun byId(providerId: String): AIProvider? = providers[providerId]

    /** Id of the user-selected brain. */
    fun activeId(): String = settings.currentActiveProviderId()

    /** The brain that answers free-form questions. Never null. */
    fun active(): AIProvider =
        byId(activeId()) ?: ordered().firstOrNull() ?: error("No AI providers are registered")

    fun hasKey(providerId: String): Boolean = keys.hasKey(providerId)

    /** True when the active provider can run without further setup by the user. */
    fun isActiveProviderReady(): Boolean {
        val provider = active()
        return !provider.requiresKey || keys.hasKey(provider.id)
    }

    /** One-shot completion on the active provider. */
    suspend fun chat(
        messages: List<Message>,
        options: ChatOptions = ChatOptions()
    ): ChatResponse = active().chat(messages, options)

    /** Incremental completion on the active provider. */
    fun streamChat(
        messages: List<Message>,
        options: ChatOptions = ChatOptions()
    ): Flow<ChatChunk> = active().streamChat(messages, options)

    /**
     * Round-trips a probe prompt against one provider. Failures are returned, not
     * thrown, and every message has already been redacted by the adapter.
     */
    suspend fun testConnection(providerId: String): Result<String> {
        val provider = byId(providerId)
            ?: return Result.failure(IllegalArgumentException("Unknown provider '$providerId'"))
        return provider.testConnection()
    }

    /** Status rows for Settings → AI Providers, in display order. */
    fun statuses(): List<ProviderStatus> {
        val activeId = activeId()
        val overrides = settings.modelOverrides().value
        return ordered().map { provider ->
            val override = overrides[provider.id]
            val chat = resolver.cachedSelection(provider.id, TaskType.CHAT)
            ProviderStatus(
                id = provider.id,
                displayName = provider.displayName,
                defaultModel = provider.defaultModel,
                activeModel = chat ?: provider.defaultModel,
                hasKey = keys.hasKey(provider.id),
                requiresKey = provider.requiresKey,
                isActive = provider.id == activeId,
                isLocal = provider.isLocal,
                supportsVision = provider.supportsVision,
                fingerprint = keys.fingerprints(listOf(provider.id))[provider.id].orEmpty(),
                modelOverride = override,
                chatModel = chat,
                visionModel = resolver.cachedSelection(provider.id, TaskType.VISION),
                audioModel = resolver.cachedSelection(provider.id, TaskType.AUDIO_LIVE),
                cacheAgeMillis = resolver.cacheAgeMillis(provider.id)
            )
        }
    }
}
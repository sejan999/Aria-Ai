package com.aria.ai.core.ai

import com.aria.ai.core.ai.model.ChatChunk
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.ChatResponse
import com.aria.ai.core.ai.model.Message
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
    val modelOverride: String? = null
) {
    /** True when the provider is usable right now. */
    val ready: Boolean get() = !requiresKey || hasKey
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
    private val keys: KeyProvider
) {

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
            ProviderStatus(
                id = provider.id,
                displayName = provider.displayName,
                defaultModel = provider.defaultModel,
                activeModel = override ?: provider.defaultModel,
                hasKey = keys.hasKey(provider.id),
                requiresKey = provider.requiresKey,
                isActive = provider.id == activeId,
                isLocal = provider.isLocal,
                supportsVision = provider.supportsVision,
                fingerprint = keys.fingerprints(listOf(provider.id))[provider.id].orEmpty(),
                modelOverride = override
            )
        }
    }
}
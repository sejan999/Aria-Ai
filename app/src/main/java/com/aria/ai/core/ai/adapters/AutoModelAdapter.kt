package com.aria.ai.core.ai.adapters

import com.aria.ai.core.ai.AIProvider
import com.aria.ai.core.ai.AUTO_MODEL_LABEL
import com.aria.ai.core.ai.AutoModelSelector
import com.aria.ai.core.ai.ModelResolver
import com.aria.ai.core.ai.model.ModelInfo
import com.aria.ai.core.ai.model.TaskType

/**
 * Shared implementation of "discover → cache 24h → select per task" for every
 * cloud adapter, so the behaviour is provably identical across providers and no
 * adapter can accidentally hardcode a model id.
 *
 * Subclasses keep their own transport concerns (HTTP, wire format) and simply
 * call [resolveModel] before issuing a request.
 */
abstract class AutoModelAdapter(
    protected val resolver: ModelResolver,
    protected val selector: AutoModelSelector
) : AIProvider {

    /**
     * The credential for this provider at call time, or null when unset.
     * On-device providers return an empty string instead of null.
     */
    protected abstract fun apiKeyOrNull(): String?

    /**
     * Model id for [task], discovered and cached on first use.
     * Throws [com.aria.ai.core.ai.MissingApiKeyException] when a key is required
     * but absent, and `ModelDiscoveryFailedException` when the provider cannot
     * serve the task.
     */
    protected suspend fun resolveModel(task: TaskType): String =
        resolver.resolve(selector, id, apiKeyOrNull(), task)

    /** Cached model id for display; [AUTO_MODEL_LABEL] until discovered. */
    override val defaultModel: String
        get() = resolver.cachedSelection(id, TaskType.CHAT) ?: AUTO_MODEL_LABEL

    override suspend fun currentModelFor(task: TaskType): String? =
        resolver.resolveOrNull(selector, id, apiKeyOrNull(), task)

    override suspend fun refreshModels() {
        resolver.invalidate(id)
    }

    override suspend fun availableModels(): List<ModelInfo> =
        runCatching { resolver.catalogue(selector, id, apiKeyOrNull()) }.getOrDefault(emptyList())
}
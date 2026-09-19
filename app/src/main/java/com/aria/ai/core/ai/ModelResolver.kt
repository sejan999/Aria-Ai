package com.aria.ai.core.ai

import com.aria.ai.core.ai.model.ModelInfo
import com.aria.ai.core.ai.model.TaskType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single implementation of "discover once, cache for 24h, select per task".
 *
 * Every adapter delegates here instead of repeating the cache dance, so the
 * 24-hour TTL and the fallback semantics are guaranteed identical across all
 * seven providers. Adapters hold their own [AutoModelSelector] and pass it in.
 */
@Singleton
class ModelResolver @Inject constructor(
    private val cache: ModelCache
) {

    /**
     * Resolves the model id to use for [task].
     *
     * Order of operations:
     *  1. per-(provider, task) selection cache;
     *  2. provider catalogue cache;
     *  3. live `ListModels` call, then rank it.
     *
     * @throws InvalidApiKeyException / PermissionDeniedException /
     *   ModelDiscoveryFailedException from the selector.
     */
    suspend fun resolve(
        selector: AutoModelSelector,
        providerId: String,
        apiKey: String?,
        task: TaskType
    ): String {
        cache.getSelection(providerId, task)?.let { return it }

        val available = catalogue(selector, providerId, apiKey)
        val selected = selector.selectBestModel(available, task)
        cache.putSelection(providerId, task, selected)
        return selected
    }

    /**
     * UI-friendly variant: returns null instead of throwing when the provider
     * simply cannot serve [task] (Anthropic/Mistral realtime audio) or when the
     * catalogue is unreachable. Used by Settings to render "N/A".
     */
    suspend fun resolveOrNull(
        selector: AutoModelSelector,
        providerId: String,
        apiKey: String?,
        task: TaskType
    ): String? = runCatching { resolve(selector, providerId, apiKey, task) }.getOrNull()

    /** Returns the provider catalogue, reusing the 24-hour cache when warm. */
    suspend fun catalogue(
        selector: AutoModelSelector,
        providerId: String,
        apiKey: String?
    ): List<ModelInfo> {
        cache.get(providerId)?.let { return it }

        val key = apiKey
        if (key.isNullOrBlank() && selector !is KeylessSelector) {
            throw MissingApiKeyException(providerId)
        }
        val fetched = selector.listAvailableModels(key.orEmpty())
        if (fetched.isNotEmpty()) cache.put(providerId, fetched)
        return fetched
    }

    /** Drops cached data so the next call re-discovers (Refresh Models). */
    fun invalidate(providerId: String) = cache.invalidate(providerId)

    fun invalidateAll() = cache.invalidateAll()

    /** Milliseconds since the catalogue was cached, for the Settings caption. */
    fun cacheAgeMillis(providerId: String): Long? = cache.ageMillis(providerId)

    /**
     * Synchronous read of an already-resolved selection, so non-suspend display
     * paths (e.g. `AIProvider.defaultModel`) can show the live model id.
     */
    fun cachedSelection(providerId: String, task: TaskType): String? =
        cache.getSelection(providerId, task)
}

/**
 * Marker for selectors that need no credential (on-device Gemma), so the
 * resolver knows an empty API key is legitimate rather than an error.
 */
interface KeylessSelector

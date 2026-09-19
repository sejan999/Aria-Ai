package com.aria.ai.core.ai

import com.aria.ai.core.ai.model.ModelInfo
import com.aria.ai.core.ai.model.TaskType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-level, thread-safe, in-memory cache for automatic model selection.
 *
 *  * Level 1 — the raw ListModels result per provider.
 *  * Level 2 — the resolved model id per (provider, task) pair.
 *
 * Both expire after [TTL_MILLIS] (24 hours). Nothing is persisted: model
 * catalogues change often and a stale entry would silently pin Aria to a
 * retired model, so a process restart always re-discovers.
 */
@Singleton
class ModelCache @Inject constructor() {

    private data class Entry<T>(val value: T, val storedAt: Long)

    private val models = ConcurrentHashMap<String, Entry<List<ModelInfo>>>()
    private val selections = ConcurrentHashMap<String, Entry<String>>()

    private fun isFresh(storedAt: Long): Boolean =
        System.currentTimeMillis() - storedAt < TTL_MILLIS

    // ------------------------------------------------------------ model lists

    /** Cached catalogue for [providerId], or null when missing/expired. */
    fun get(providerId: String): List<ModelInfo>? {
        val entry = models[providerId] ?: return null
        if (!isFresh(entry.storedAt)) {
            models.remove(providerId)
            return null
        }
        return entry.value
    }

    fun put(providerId: String, list: List<ModelInfo>) {
        models[providerId] = Entry(list, System.currentTimeMillis())
    }

    /** True when a usable catalogue is cached (used to avoid needless HTTP). */
    fun has(providerId: String): Boolean = get(providerId) != null

    // -------------------------------------------------------------- selections

    /** Cached model id for a (provider, task) pair, or null when absent. */
    fun getSelection(providerId: String, task: TaskType): String? {
        val key = task.cacheKey(providerId)
        val entry = selections[key] ?: return null
        if (!isFresh(entry.storedAt)) {
            selections.remove(key)
            return null
        }
        return entry.value
    }

    fun putSelection(providerId: String, task: TaskType, modelId: String) {
        selections[task.cacheKey(providerId)] = Entry(modelId, System.currentTimeMillis())
    }

    // ----------------------------------------------------------- invalidation

    /**
     * Drops both levels for one provider so the next call re-discovers.
     * This is what the UI's "Refresh Models" button triggers.
     */
    fun invalidate(providerId: String) {
        models.remove(providerId)
        for (task in TaskType.entries) {
            selections.remove(task.cacheKey(providerId))
        }
    }

    fun invalidateAll() {
        models.clear()
        selections.clear()
    }

    /** Milliseconds since the catalogue was stored, or null when not cached. */
    fun ageMillis(providerId: String): Long? {
        val entry = models[providerId] ?: return null
        return System.currentTimeMillis() - entry.storedAt
    }

    companion object {
        /** 24 hours. */
        const val TTL_MILLIS: Long = 86_400_000L
    }
}
package com.aria.ai.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** App-private preference store owned by [ProviderSettingsRepository]. */
private val Context.ariaSettingsStore: DataStore<Preferences> by
    preferencesDataStore(name = "aria_settings")

/**
 * Single source of truth for every Aria preference: the active brain, per-provider
 * model overrides, the on-device Gemma path, the wake word and auto-speak.
 *
 * Values live in a Preferences DataStore (app-private, no key material is ever
 * written here — API keys belong to `ApiKeyVault`). Every flow is exposed as a
 * cached [StateFlow], so adapters and the registry can read the current value
 * synchronously at call time without touching disk.
 */
@Singleton
class ProviderSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope
) {

    private val store: DataStore<Preferences> = context.ariaSettingsStore

    /** A corrupt/unreadable file degrades to defaults instead of crashing the UI. */
    private val preferences: Flow<Preferences> = store.data.catch { failure ->
        if (failure is IOException) emit(emptyPreferences()) else throw failure
    }

    val autoSpeak: StateFlow<Boolean> = preferences
        .map { prefs -> prefs[KEY_AUTO_SPEAK] ?: DEFAULT_AUTO_SPEAK }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_AUTO_SPEAK)

    val wakeWord: StateFlow<String> = preferences
        .map { prefs -> prefs[KEY_WAKE_WORD]?.takeIf { it.isNotBlank() } ?: DEFAULT_WAKE_WORD }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_WAKE_WORD)

    /** Absolute path of the on-device Gemma model, or null while unset. */
    val gemmaModelPath: StateFlow<String?> = preferences
        .map { prefs -> prefs[KEY_GEMMA_PATH]?.takeIf { it.isNotBlank() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The brain answering free-form questions; always a known provider id. */
    val activeProviderId: StateFlow<String> = preferences
        .map { prefs ->
            prefs[KEY_ACTIVE_PROVIDER]?.takeIf { ProviderIds.ALL.contains(it) } ?: ProviderIds.GEMINI
        }
        .stateIn(scope, SharingStarted.Eagerly, ProviderIds.GEMINI)

    private val overrides: StateFlow<Map<String, String>> = preferences
        .map { prefs ->
            ProviderIds.ALL
                .mapNotNull { id -> prefs[overrideKey(id)]?.takeIf { it.isNotBlank() }?.let { id to it } }
                .toMap()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Per-provider model overrides, keyed by provider id. */
    fun modelOverrides(): StateFlow<Map<String, String>> = overrides

    // ------------------------------------------------------- synchronous readers

    fun currentAutoSpeak(): Boolean = autoSpeak.value

    fun currentWakeWord(): String = wakeWord.value

    fun currentGemmaModelPath(): String? = gemmaModelPath.value

    fun currentActiveProviderId(): String = activeProviderId.value

    fun currentModelOverride(providerId: String): String? = overrides.value[providerId]

    // ------------------------------------------------------------------ writers

    suspend fun setAutoSpeak(enabled: Boolean) {
        store.edit { prefs -> prefs[KEY_AUTO_SPEAK] = enabled }
    }

    suspend fun setWakeWord(value: String) {
        val clean = value.trim()
        store.edit { prefs ->
            if (clean.isEmpty()) prefs.remove(KEY_WAKE_WORD) else prefs[KEY_WAKE_WORD] = clean
        }
    }

    suspend fun setGemmaModelPath(value: String) {
        val clean = value.trim()
        store.edit { prefs ->
            if (clean.isEmpty()) prefs.remove(KEY_GEMMA_PATH) else prefs[KEY_GEMMA_PATH] = clean
        }
    }

    suspend fun setActiveProvider(providerId: String) {
        val clean = providerId.trim().lowercase()
        if (!ProviderIds.ALL.contains(clean)) return
        store.edit { prefs -> prefs[KEY_ACTIVE_PROVIDER] = clean }
    }

    suspend fun setModelOverride(providerId: String, model: String) {
        val clean = model.trim()
        store.edit { prefs ->
            if (clean.isEmpty()) prefs.remove(overrideKey(providerId)) else prefs[overrideKey(providerId)] = clean
        }
    }

    /** Returns every preference to its factory default (used by "reset Aria"). */
    suspend fun reset() {
        store.edit { prefs -> prefs.clear() }
    }

    private fun overrideKey(providerId: String) = stringPreferencesKey(MODEL_PREFIX + providerId)

    companion object {
        const val DEFAULT_WAKE_WORD = "aria"
        const val DEFAULT_AUTO_SPEAK = true

        private const val MODEL_PREFIX = "model_override_"

        private val KEY_AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        private val KEY_WAKE_WORD = stringPreferencesKey("wake_word")
        private val KEY_GEMMA_PATH = stringPreferencesKey("gemma_model_path")
        private val KEY_ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
    }
}
package com.aria.ai.data.vault

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware-backed API key vault.
 *
 * Storage: EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV keys)
 * keyed by a [MasterKey] that lives in the Android Keystore — the raw key never
 * touches app memory in plaintext form and is never written to disk unencrypted.
 *
 * Fallback: if the Keystore/EncryptedSharedPreferences pair is unavailable (rare —
 * e.g. a corrupted keystore after a factory reset) keys are held in memory for the
 * current session ONLY. They are never written to disk in plaintext, so the worst
 * case is that the user has to paste the key again after a restart.
 */
@Singleton
class ApiKeyVault @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Null when the encrypted store could not be opened (memory-only session). */
    private val prefs: SharedPreferences? = openVault()

    /** Session-only store used when the encrypted store is unavailable. */
    private val sessionKeys = ConcurrentHashMap<String, String>()

    private fun openVault(): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "Encrypted vault unavailable (${t.javaClass.simpleName}); keys stay in memory " +
                    "for this session only and are never written to disk"
            )
            null
        }
    }

    /** True when keys are backed by the Keystore-encrypted store. */
    fun isEncrypted(): Boolean = prefs != null

    /** Returns the stored key or null when the provider is not configured. */
    fun getKey(providerId: String): String? {
        val stored = prefs?.getString(keyName(providerId), null) ?: sessionKeys[providerId]
        return stored?.takeIf { it.isNotBlank() }
    }

    fun hasKey(providerId: String): Boolean = getKey(providerId) != null

    fun setKey(providerId: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            removeKey(providerId)
            return
        }
        val store = prefs
        if (store != null) {
            store.edit().putString(keyName(providerId), trimmed).apply()
        } else {
            sessionKeys[providerId] = trimmed
        }
        Log.i(TAG, "Stored key for '$providerId' (${redact(trimmed)})")
    }

    fun removeKey(providerId: String) {
        prefs?.edit()?.remove(keyName(providerId))?.apply()
        sessionKeys.remove(providerId)
    }

    /** Irreversibly removes every provider key from the device. */
    fun wipeAll() {
        prefs?.edit()?.clear()?.apply()
        sessionKeys.clear()
        Log.i(TAG, "Vault wiped: all provider keys removed")
    }

    /**
     * Short, non-reversible-looking fingerprint for UI display. Printing this is
     * safe: it exposes only a handful of characters of an already-stored key.
     */
    fun fingerprint(providerId: String): String = redact(getKey(providerId))

    private fun keyName(providerId: String): String = KEY_PREFIX + providerId

    companion object {
        private const val TAG = "AriaVault"
        private const val PREFS_NAME = "aria_secure_vault"
        private const val KEY_PREFIX = "aria.key."

        fun redact(value: String?): String {
            if (value.isNullOrBlank()) return " not set"
            val v = value.trim()
            return when {
                v.length <= 8 -> "••••••••"
                else -> "${v.take(3)}••••••••${v.takeLast(3)} (${v.length} chars)"
            }
        }
    }
}
package com.aria.ai.core.ai

import com.aria.ai.data.vault.ApiKeyVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single gateway between adapters and the encrypted vault.
 *
 * Adapters call [requireKey] at request time (never at construction time) so a
 * key that is added or rotated in Settings takes effect on the very next call
 * with no app restart.
 */
@Singleton
class KeyProvider @Inject constructor(
    private val vault: ApiKeyVault
) {

    /** Null when the provider has no stored key. */
    fun getKey(providerId: String): String? = vault.getKey(providerId)

    fun hasKey(providerId: String): Boolean = vault.hasKey(providerId)

    /**
     * @throws MissingApiKeyException when the provider is not configured.
     */
    fun requireKey(providerId: String, displayName: String = providerId): String =
        getKey(providerId) ?: throw MissingApiKeyException.forProvider(providerId, displayName)

    /** Safe-to-log fingerprints for every known provider. */
    fun fingerprints(providerIds: List<String>): Map<String, String> =
        providerIds.associateWith { vault.fingerprint(it) }
}
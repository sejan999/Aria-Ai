package com.aria.ai.data.vault

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session gate for the encrypted API key vault.
 *
 * On a device that has a lockscreen, the vault UI stays hidden behind the
 * platform credential prompt (PIN / pattern / password / biometric) and is only
 * revealed once the user authenticates — the unlock lasts for the process
 * lifetime and is dropped by [lockSession] (the "Lock now" action).
 *
 * On a device without any lockscreen there is no credential to ask for, so the
 * vault is treated as unlocked; the keys themselves remain protected by the
 * Android Keystore either way.
 */
@Singleton
class VaultLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val keyguard: KeyguardManager? =
        context.getSystemService(KeyguardManager::class.java)

    private val sessionUnlocked = AtomicBoolean(false)

    /** True when the device can challenge the user for a credential. */
    fun isDeviceSecure(): Boolean = runCatching { keyguard?.isDeviceSecure == true }
        .getOrDefault(false)

    /** True when the vault contents may be shown or edited right now. */
    fun isVaultUnlocked(): Boolean = !isDeviceSecure() || sessionUnlocked.get()

    /** Called after a successful credential prompt. */
    fun markSessionUnlocked() {
        sessionUnlocked.set(true)
    }

    /** Forgets the session unlock ("Lock now"). */
    fun lockSession() {
        sessionUnlocked.set(false)
    }

    /**
     * Intent for the platform credential prompt, or null when the device has no
     * lockscreen (in which case no unlock is needed).
     */
    @Suppress("DEPRECATION")
    fun credentialIntent(): Intent? {
        if (!isDeviceSecure()) return null
        val manager = keyguard ?: return null
        return runCatching {
            manager.createConfirmDeviceCredentialIntent(
                "Unlock Aria vault",
                "Authenticate to view or edit your stored API keys"
            )
        }.getOrNull()
    }
}
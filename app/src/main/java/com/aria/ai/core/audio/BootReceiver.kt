package com.aria.ai.core.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Restart the always-listening foreground service after the device reboots.
 *
 * The toggle in Settings is persisted through [ProviderSettingsRepository];
 * this receiver honours it by re-launching [AriaListenerService] only when the
 * user had the listener enabled. The check is a plain read of the persisted
 * preference, so no API-key or personal data is ever involved.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS = "aria_listener_prefs"
        private const val KEY_ENABLED = "always_listening"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        val serviceIntent = Intent(context, AriaListenerService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}

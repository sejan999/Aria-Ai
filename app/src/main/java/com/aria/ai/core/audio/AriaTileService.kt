package com.aria.ai.core.audio

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Quick Settings tile to toggle the always-listening foreground service.
 *
 * The tile reflects the service's live state: active when
 * [AriaListenerService] is running, inactive otherwise.
 */
class AriaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, AriaListenerService::class.java)
        if (isListeningActive()) {
            // Stop: the service handles ACTION_STOP and calls stopSelf.
            ContextCompat.startForegroundService(
                this,
                Intent(this, AriaListenerService::class.java).apply {
                    action = AriaListenerService.ACTION_STOP
                }
            )
            prefs().edit().putBoolean("always_listening", false).apply()
        } else {
            ContextCompat.startForegroundService(this, intent)
            prefs().edit().putBoolean("always_listening", true).apply()
        }
        updateTile()
    }

    private fun isListeningActive(): Boolean =
        prefs().getBoolean("always_listening", false)

    private fun prefs() = getSharedPreferences("aria_listener_prefs", MODE_PRIVATE)

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = isListeningActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (active) "Listening" else "Off"
        tile.updateTile()
    }
}

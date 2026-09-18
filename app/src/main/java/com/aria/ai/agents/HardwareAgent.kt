package com.aria.ai.agents

import com.aria.ai.core.system.HardwareController
import com.aria.ai.core.system.MediaControllerManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device specialist: flashlight, vibration, brightness, volume, battery and the
 * system settings panels. All work is delegated to [HardwareController] and
 * [MediaControllerManager], so permissions and capability checks live in one place.
 */
@Singleton
class HardwareAgent @Inject constructor(
    private val hardware: HardwareController,
    private val media: MediaControllerManager
) : AriaAgent {

    override val id: String = "hardware"
    override val name: String = "Hardware Agent"
    override val description: String = "Flashlight, volume, brightness, vibration, Wi-Fi panels"
    override val keywords: List<String> = listOf(
        "flashlight", "torch", "light on", "light off",
        "volume", "louder", "quieter", "mute", "unmute",
        "brightness", "brighter", "dimmer",
        "vibrate", "buzz",
        "battery",
        "wifi", "wi-fi", "bluetooth", "airplane mode"
    )

    override suspend fun handle(intent: String, utterance: String): AgentResult {
        val heard = utterance.lowercase()
        val message = when {
            heard.contains("flashlight") || heard.contains("torch") ->
                if (heard.contains("off")) hardware.torch(false) else hardware.toggleTorch()

            heard.contains("louder") || heard.contains("volume up") -> media.volumeUp()

            heard.contains("quieter") || heard.contains("volume down") -> media.volumeDown()

            heard.contains("volume") && percentFrom(heard) != null ->
                media.setVolumePercent(percentFrom(heard)!!)

            heard.contains("mute") && !heard.contains("unmute") -> media.mute()

            heard.contains("unmute") -> media.unmute()

            heard.contains("brighter") -> hardware.brightnessUp()

            heard.contains("dimmer") || heard.contains("dim ") -> hardware.brightnessDown()

            heard.contains("brightness") && percentFrom(heard) != null ->
                hardware.setBrightness(percentFrom(heard)!!)

            heard.contains("vibrate") || heard.contains("buzz") -> hardware.vibrate()

            heard.contains("battery") -> {
                val level = hardware.batteryLevel()
                if (level >= 0) "Battery is at $level%." else "I cannot read the battery level on this device."
            }

            heard.contains("wifi") || heard.contains("wi-fi") -> hardware.openSettingsPanel("wifi")

            heard.contains("bluetooth") -> hardware.openSettingsPanel("bluetooth")

            else -> "I can control the flashlight, volume, brightness, vibration and open Wi-Fi or Bluetooth settings."
        }

        val success = !message.startsWith("I could not") && !message.startsWith("Grant Aria")
        return AgentResult(id, success, message)
    }

    /** Parses "50%" or "volume 30" style numbers out of an utterance. */
    private fun percentFrom(heard: String): Int? {
        val match = Regex("(\\d{1,3})\\s*%?").find(heard) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return value.coerceIn(0, 100)
    }
}
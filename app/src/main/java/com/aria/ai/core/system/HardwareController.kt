package com.aria.ai.core.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Physical-device control surface for the hardware agent.
 *
 * Everything the user asks for is executed here through official platform APIs
 * only: torch via [CameraManager], vibration via the system vibrator, screen
 * brightness via `Settings.System` (which the user must authorise once) and the
 * settings panels via standard `Settings.ACTION_*` intents.
 *
 * Every method returns a short, speakable sentence — permission problems start
 * with "Grant Aria" and hard failures with "I could not", which is how the agent
 * decides whether an action actually succeeded.
 */
@Singleton
class HardwareController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val cameraManager: CameraManager? =
        context.getSystemService(CameraManager::class.java)

    private val vibrator: Vibrator? = resolveVibrator()

    private var torchOn = false

    private fun resolveVibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private fun has(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------- torch

    /** Turns the torch on or off. */
    fun torch(on: Boolean): String {
        val manager = cameraManager ?: return "I could not reach the camera service on this device."
        val cameraId = torchCameraId() ?: return "I could not find a flashlight on this device."
        return runCatching {
            manager.setTorchMode(cameraId, on)
            torchOn = on
            if (on) "Flashlight on." else "Flashlight off."
        }.getOrElse { failure ->
            Log.w(TAG, "Torch switch failed (${failure.javaClass.simpleName})")
            "I could not switch the flashlight."
        }
    }

    /** Flips the torch to its opposite state. */
    fun toggleTorch(): String = torch(!torchOn)

    fun isTorchOn(): Boolean = torchOn

    private fun torchCameraId(): String? {
        val manager = cameraManager ?: return null
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        var fallback: String? = null
        for (id in ids) {
            val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!flash) continue
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    // --------------------------------------------------------------- vibration

    /** One short buzz; [durationMs] is clamped to something pleasant. */
    fun vibrate(durationMs: Long = 400L): String {
        if (!has(Manifest.permission.VIBRATE)) {
            return "Grant Aria the vibration permission to use haptics."
        }
        val motor = vibrator ?: return "I could not find a vibrator on this device."
        val millis = durationMs.coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS)
        return runCatching {
            motor.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            "Vibrating."
        }.getOrElse { "I could not start the vibration." }
    }

    // -------------------------------------------------------------- brightness

    fun brightnessUp(): String = adjustBrightness(BRIGHTNESS_STEP)

    fun brightnessDown(): String = adjustBrightness(-BRIGHTNESS_STEP)

    /** Sets screen brightness to [percent] (0..100). */
    fun setBrightness(percent: Int): String {
        val target = percent.coerceIn(MIN_BRIGHTNESS_PERCENT, 100)
        return writeBrightness(percentToRaw(target))
    }

    /** Current brightness as 0..100, or -1 when it cannot be read. */
    fun brightnessPercent(): Int {
        val raw = readBrightness() ?: return -1
        return ((raw * 100) / MAX_RAW_BRIGHTNESS).coerceIn(0, 100)
    }

    private fun adjustBrightness(delta: Int): String {
        val current = readBrightness() ?: return "I could not read the screen brightness."
        val nextRaw = (current + delta).coerceIn(percentToRaw(MIN_BRIGHTNESS_PERCENT), MAX_RAW_BRIGHTNESS)
        return writeBrightness(nextRaw)
    }

    private fun readBrightness(): Int? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull()

    private fun writeBrightness(raw: Int): String {
        if (!Settings.System.canWrite(context)) {
            return "Grant Aria \"Modify system settings\" (Settings → Apps → Aria Ai) so I can change brightness."
        }
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
            "Brightness set to ${((raw * 100) / MAX_RAW_BRIGHTNESS).coerceIn(0, 100)}%."
        }.getOrElse { "I could not change the brightness." }
    }

    private fun percentToRaw(percent: Int): Int = (MAX_RAW_BRIGHTNESS * percent) / 100

    // ------------------------------------------------------------------ battery

    /** Battery percentage, or -1 when the system does not report it. */
    fun batteryLevel(): Int {
        val intent = batteryIntent() ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return ((level * 100) / scale).coerceIn(0, 100)
    }

    fun isCharging(): Boolean {
        val intent = batteryIntent() ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun batteryIntent(): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    // ---------------------------------------------------------- settings panels

    /**
     * Opens a system settings screen. [topic] is a loose phrase such as "wifi",
     * "bluetooth", "display", "sound", "battery" or "apps".
     */
    fun openSettingsPanel(topic: String): String {
        val key = topic.trim().lowercase()
        val (action, label) = when {
            key.contains("wifi") || key.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi settings"
            key.contains("blue") -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth settings"
            key.contains("data") || key.contains("mobile") -> Settings.ACTION_DATA_ROAMING_SETTINGS to "mobile data settings"
            key.contains("airplane") || key.contains("flight") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "airplane mode settings"
            key.contains("display") || key.contains("screen") -> Settings.ACTION_DISPLAY_SETTINGS to "display settings"
            key.contains("sound") || key.contains("volume") -> Settings.ACTION_SOUND_SETTINGS to "sound settings"
            key.contains("battery") -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "battery settings"
            key.contains("location") || key.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "location settings"
            key.contains("nfc") -> Settings.ACTION_NFC_SETTINGS to "NFC settings"
            key.contains("accessib") -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "accessibility settings"
            key.contains("notif") -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to "notification access"
            key.contains("app") -> Settings.ACTION_APPLICATION_SETTINGS to "app settings"
            key.contains("developer") -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to "developer options"
            key.contains("storage") -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "storage settings"
            else -> Settings.ACTION_SETTINGS to "system settings"
        }

        return launch(Intent(action), label)
    }

    /** Opens the "Modify system settings" screen for Aria itself. */
    fun openWriteSettingsPermission(): String {
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}")
        )
        return launch(intent, "Aria's system-settings permission")
    }

    private fun launch(intent: Intent, label: String): String = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Opened $label."
    }.getOrElse { "I could not open $label on this device." }

    private companion object {
        const val TAG = "AriaHardware"
        const val MAX_RAW_BRIGHTNESS = 255
        const val MIN_BRIGHTNESS_PERCENT = 5
        const val BRIGHTNESS_STEP = 38
        const val MIN_VIBRATION_MS = 40L
        const val MAX_VIBRATION_MS = 3_000L
    }
}
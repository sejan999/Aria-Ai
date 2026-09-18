package com.aria.ai.core.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.aria.ai.data.local.AutomationDao
import com.aria.ai.data.local.AutomationEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of executing an automation action. */
data class AutomationOutcome(val success: Boolean, val message: String)

/**
 * The automation runtime: stores trigger → action rules in Room and executes them
 * through the same controllers the sub-agents use.
 *
 * Rule payloads are JSON maps (serialised with Moshi), which keeps the Room schema
 * trivial while letting every action type carry whatever parameters it needs.
 */
@Singleton
class AutomationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AutomationDao,
    private val moshi: Moshi,
    private val hardware: HardwareController,
    private val media: MediaControllerManager,
    private val calls: CallController
) {

    fun rules(): Flow<List<AutomationEntity>> = dao.observeAll()

    suspend fun ruleCount(): Int = dao.count()

    suspend fun listedRules(): List<AutomationEntity> = dao.all()

    suspend fun addRule(
        trigger: String,
        actionType: String,
        payload: Map<String, String>
    ): String {
        val cleanTrigger = trigger.trim().lowercase()
        if (cleanTrigger.isBlank()) return "An automation needs a trigger phrase."
        dao.insert(
            AutomationEntity(
                trigger = cleanTrigger,
                actionType = actionType.uppercase(),
                actionPayload = encode(payload)
            )
        )
        return "Automation saved: when you say \"$cleanTrigger\", I will run ${actionType.uppercase()}."
    }

    suspend fun removeRule(trigger: String): String {
        val removed = dao.deleteByTrigger(trigger.trim().lowercase())
        return if (removed > 0) {
            "Removed the automation for \"$trigger\"."
        } else {
            "I found no automation for \"$trigger\"."
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    /** Runs the first enabled rule whose trigger phrase appears in the utterance. */
    suspend fun evaluate(utterance: String): AutomationOutcome? {
        val heard = utterance.lowercase()
        val match = dao.enabled().firstOrNull { heard.contains(it.trigger) } ?: return null
        return execute(match.actionType, decode(match.actionPayload))
    }

    /** Executes one action type with its decoded payload. */
    suspend fun execute(actionType: String, payload: Map<String, String>): AutomationOutcome {
        val message = when (actionType.uppercase()) {
            TYPE_OPEN_APP -> openApp(payload["query"].orEmpty())
            TYPE_TORCH -> hardware.torch(payload["state"]?.toBoolean() ?: true)
            TYPE_VIBRATE -> hardware.vibrate(payload["durationMs"]?.toLongOrNull() ?: 400L)
            TYPE_BRIGHTNESS -> hardware.setBrightness(payload["percent"]?.toIntOrNull() ?: 50)
            TYPE_VOLUME -> media.setVolumePercent(payload["percent"]?.toIntOrNull() ?: 50)
            TYPE_PLAY_PAUSE -> media.playPause()
            TYPE_MUTE -> media.mute()
            TYPE_CALL -> calls.placeCall(payload["number"].orEmpty())
            TYPE_END_CALL -> calls.endCall()
            TYPE_SETTINGS_PANEL -> hardware.openSettingsPanel(payload["topic"].orEmpty())
            TYPE_NAVIGATE -> navigate(payload["action"].orEmpty())
            else -> "Unknown automation action '$actionType'."
        }
        val success = !message.startsWith("Unknown") && !message.startsWith("I could not")
        return AutomationOutcome(success, message)
    }

    private fun navigate(action: String): String {
        val mapped = when (action.lowercase()) {
            "home" -> AriaAccessibilityService.Action.HOME
            "back" -> AriaAccessibilityService.Action.BACK
            "recents" -> AriaAccessibilityService.Action.RECENTS
            "notifications" -> AriaAccessibilityService.Action.NOTIFICATIONS
            "quick_settings", "quick settings" -> AriaAccessibilityService.Action.QUICK_SETTINGS
            "lock", "lock_screen" -> AriaAccessibilityService.Action.LOCK_SCREEN
            else -> null
        } ?: return "I do not know the navigation action '$action'."

        return if (AriaAccessibilityService.perform(mapped)) {
            "Performed ${action.lowercase()}."
        } else {
            "Enable Aria in Accessibility settings so I can navigate for you."
        }
    }

    private fun openApp(query: String): String {
        if (query.isBlank()) return "I need an app name to open."
        val manager = context.packageManager
        val installed = runCatching { manager.getInstalledApplications(0) }
            .getOrDefault(emptyList())

        val needle = query.trim().lowercase()
        val match = installed.firstOrNull { app ->
            manager.getApplicationLabel(app).toString().lowercase().contains(needle) ||
                app.packageName.substringAfterLast('.').lowercase().contains(needle)
        } ?: return "I could not find an app matching \"$query\"."

        val label = manager.getApplicationLabel(match).toString()
        val launch = manager.getLaunchIntentForPackage(match.packageName)
            ?: return "$label has no launch screen."

        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opening $label."
        }.getOrElse { "I could not open $label." }
    }

    private fun encode(payload: Map<String, String>): String = runCatching {
        moshi.adapter<Map<String, String>>(stringMapType()).toJson(payload)
    }.getOrElse {
        Log.w(TAG, "Payload serialisation failed (${it.javaClass.simpleName})")
        EMPTY_JSON
    }

    private fun decode(json: String): Map<String, String> = runCatching {
        moshi.adapter<Map<String, String>>(stringMapType()).fromJson(json) ?: emptyMap()
    }.getOrElse { emptyMap() }

    private fun stringMapType() =
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)

    companion object {
        const val TAG = "AriaAutomation"
        private const val EMPTY_JSON = "{}"

        const val TYPE_OPEN_APP = "OPEN_APP"
        const val TYPE_TORCH = "TORCH"
        const val TYPE_VIBRATE = "VIBRATE"
        const val TYPE_BRIGHTNESS = "BRIGHTNESS"
        const val TYPE_VOLUME = "VOLUME"
        const val TYPE_PLAY_PAUSE = "PLAY_PAUSE"
        const val TYPE_MUTE = "MUTE"
        const val TYPE_CALL = "CALL"
        const val TYPE_END_CALL = "END_CALL"
        const val TYPE_SETTINGS_PANEL = "SETTINGS_PANEL"
        const val TYPE_NAVIGATE = "NAVIGATE"

        val ACTION_TYPES: List<String> = listOf(
            TYPE_OPEN_APP, TYPE_TORCH, TYPE_VIBRATE, TYPE_BRIGHTNESS, TYPE_VOLUME,
            TYPE_PLAY_PAUSE, TYPE_MUTE, TYPE_CALL, TYPE_END_CALL, TYPE_SETTINGS_PANEL,
            TYPE_NAVIGATE
        )

        /** URI used for the "display over other apps" permission screen. */
        fun overlayPermissionUri(packageName: String): Uri = Uri.parse("package:$packageName")
    }
}
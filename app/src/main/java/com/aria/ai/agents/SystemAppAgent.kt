package com.aria.ai.agents

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.aria.ai.core.system.AriaAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System + app specialist: opens installed apps, deep-links into system settings
 * and drives the global navigation gestures through the accessibility surface.
 */
@Singleton
class SystemAppAgent @Inject constructor(
    @ApplicationContext private val context: Context
) : AriaAgent {

    override val id: String = "system_apps"
    override val name: String = "System & Apps Agent"
    override val description: String = "Opens apps and settings, performs navigation"
    override val keywords: List<String> = listOf(
        "open ", "launch ", "settings", "go home", "go back", "home screen", "recents",
        "recent apps", "notification shade", "notifications panel", "quick settings",
        "lock the phone", "lock my phone", "lock screen", "app info"
    )

    override suspend fun handle(intent: String, utterance: String): AgentResult {
        val heard = utterance.lowercase()

        val message = when {
            heard.contains("go home") || heard.trim() == "home" || heard.contains("home screen") ->
                navigate(AriaAccessibilityService.Action.HOME, "home")

            heard.contains("go back") -> navigate(AriaAccessibilityService.Action.BACK, "back")

            heard.contains("recents") || heard.contains("recent apps") ->
                navigate(AriaAccessibilityService.Action.RECENTS, "recents")

            heard.contains("quick settings") ->
                navigate(AriaAccessibilityService.Action.QUICK_SETTINGS, "quick settings")

            heard.contains("notification shade") || heard.contains("notifications panel") ->
                navigate(AriaAccessibilityService.Action.NOTIFICATIONS, "the notification shade")

            heard.contains("lock the phone") || heard.contains("lock my phone") ||
                heard.contains("lock screen") ->
                navigate(AriaAccessibilityService.Action.LOCK_SCREEN, "the lock screen")

            heard.contains("wifi") || heard.contains("wi-fi") ->
                open(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")

            heard.contains("bluetooth") -> open(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")

            heard.contains("display") -> open(Settings.ACTION_DISPLAY_SETTINGS, "display settings")

            heard.contains("sound") -> open(Settings.ACTION_SOUND_SETTINGS, "sound settings")

            heard.contains("battery") -> open(Intent.ACTION_POWER_USAGE_SUMMARY, "battery usage")

            heard.contains("app info") || heard.contains("application info") ->
                open(Settings.ACTION_APPLICATION_SETTINGS, "app settings")

            heard.contains("developer") ->
                open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "developer options")

            heard.contains("settings") -> open(Settings.ACTION_SETTINGS, "system settings")

            else -> openApp(extractAppName(heard))
        }

        val success = !message.startsWith("I could not") && !message.startsWith("Enable Aria")
        return AgentResult(id, success, message)
    }

    private fun navigate(action: AriaAccessibilityService.Action, label: String): String =
        if (AriaAccessibilityService.perform(action)) {
            "Performed $label."
        } else {
            "Enable Aria in Accessibility settings so I can navigate for you."
        }

    private fun open(action: String, label: String): String = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Opened $label."
    }.getOrElse { "I could not open $label." }

    private fun openApp(query: String): String {
        if (query.isBlank()) return "Tell me which app to open."
        val manager = context.packageManager
        val installed = runCatching { manager.getInstalledApplications(0) }.getOrDefault(emptyList())
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

    /** "open whatsapp please" → "whatsapp". */
    private fun extractAppName(heard: String): String = heard
        .removePrefix("open ")
        .removePrefix("launch ")
        .removePrefix("start ")
        .replace("the app", "")
        .replace("app", "")
        .replace("please", "")
        .trim()

    /** Launchable app labels, newest-sorted, for discovery hints. */
    fun installedApps(limit: Int = 20): List<String> {
        val manager = context.packageManager
        return runCatching {
            manager.getInstalledApplications(0)
                .filter { manager.getLaunchIntentForPackage(it.packageName) != null }
                .map { manager.getApplicationLabel(it).toString() }
                .sorted()
                .take(limit)
        }.getOrDefault(emptyList())
    }
}
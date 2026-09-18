package com.aria.ai.agents

import com.aria.ai.core.system.AutomationEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automation specialist: creates, lists, removes and runs trigger → action rules.
 *
 * Rule grammar understood here:
 *   • "list automations"                      → prints every rule
 *   • "run <trigger>"                         → executes a saved rule
 *   • "automate <trigger> -> <ACTION> [k=v]"  → saves a rule
 *   • "delete automation <trigger>"           → removes a rule
 */
@Singleton
class AutomationAgent @Inject constructor(
    private val engine: AutomationEngine
) : AriaAgent {

    override val id: String = "automation"
    override val name: String = "Automation Agent"
    override val description: String = "Creates and runs trigger → action automations"
    override val keywords: List<String> = listOf(
        "automation", "automate", "automations", "run automation", "delete automation",
        "list automation", "when i say"
    )

    override suspend fun handle(intent: String, utterance: String): AgentResult {
        val heard = utterance.lowercase().trim()

        val message = when {
            heard.contains("list automation") || heard.contains("show automation") ||
                heard.contains("my automations") -> listRules()

            heard.contains("delete automation") || heard.contains("remove automation") -> {
                val trigger = extractAfter(heard, "automation")
                if (trigger.isBlank()) "Tell me which automation to delete." else engine.removeRule(trigger)
            }

            heard.contains("automate") || heard.contains("->") -> createRule(heard)

            else -> runExisting(heard)
        }

        val success = !message.startsWith("I could not") && !message.startsWith("I found no")
        return AgentResult(id, success, message)
    }

    private suspend fun listRules(): String {
        val rules = engine.listedRules()
        if (rules.isEmpty()) {
            return "You have no automations yet. Try: automate movie night -> TORCH state=true"
        }
        val lines = rules.joinToString("\n") { "• ${it.describe()}" }
        return "You have ${rules.size} automation(s):\n$lines\n\nActions available: " +
            AutomationEngine.ACTION_TYPES.joinToString(", ")
    }

    private suspend fun runExisting(heard: String): String {
        val outcome = engine.evaluate(heard)
            ?: return "I found no automation matching that phrase. Say \"list automations\" to see what exists."
        return outcome.message
    }

    private suspend fun createRule(heard: String): String {
        val cleaned = heard
            .removePrefix("automate")
            .removePrefix("create automation")
            .removePrefix("add automation")
            .trim()

        val trigger = extractAfter(cleaned, "")
        val segments = cleaned.split("->", "→")
        if (trigger.isBlank()) {
            return "Give the automation a trigger phrase, for example: automate movie night -> TORCH state=true"
        }
        if (segments.size < 2) {
            return "I need an action as well, like: automate movie night -> TORCH state=true"
        }

        val actionPart = segments[1].trim()
        val tokens = actionPart.split(Regex("\\s+")).filter { it.isNotBlank() }
        val actionType = tokens.firstOrNull()?.uppercase() ?: return "That action type is missing."

        if (!AutomationEngine.ACTION_TYPES.contains(actionType)) {
            return "\"$actionType\" is not an action I know. Available: " +
                AutomationEngine.ACTION_TYPES.joinToString(", ")
        }

        val payload = tokens.drop(1)
            .mapNotNull { token ->
                val split = token.split("=", limit = 2)
                if (split.size == 2) split[0].trim() to split[1].trim() else null
            }
            .toMap()

        return engine.addRule(trigger = trigger, actionType = actionType, payload = payload)
    }

    private fun extractAfter(text: String, marker: String): String {
        val source = if (marker.isBlank()) text else text.substringAfter(marker, text)
        return source
            .split("->", "→")
            .first()
            .trim()
            .trimStart('-', '>', ' ')
    }
}
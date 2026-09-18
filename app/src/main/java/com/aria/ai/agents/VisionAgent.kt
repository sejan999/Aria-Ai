package com.aria.ai.agents

import com.aria.ai.core.ai.ProviderRegistry
import com.aria.ai.core.ai.model.ChatOptions
import com.aria.ai.core.ai.model.Message
import com.aria.ai.core.vision.VisionBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vision specialist: grabs one frame of the current screen and asks the active
 * vision-capable provider to describe it. The screenshot never leaves the app
 * unless the user explicitly asks Aria to look at the screen.
 */
@Singleton
class VisionAgent @Inject constructor(
    private val registry: ProviderRegistry,
    private val vision: VisionBridge
) : AriaAgent {

    override val id: String = "vision"
    override val name: String = "Vision Agent"
    override val description: String = "Describes what is on the screen right now"
    override val keywords: List<String> = listOf(
        "what do you see", "look at my screen", "read the screen", "describe the screen",
        "what's on my screen", "whats on my screen", "screenshot", "screen vision"
    )

    override suspend fun handle(intent: String, utterance: String): AgentResult {
        val provider = registry.active()

        if (!provider.supportsVision) {
            return AgentResult(
                id,
                false,
                "${provider.displayName} cannot analyse images. Switch the active provider to Gemini, " +
                    "OpenAI, Anthropic, OpenRouter or the on-device model in Settings → AI Providers."
            )
        }

        if (!vision.isArmed) {
            return AgentResult(
                id,
                false,
                "Screen capture is off. Tap the eye button on the home screen and approve the capture " +
                    "prompt, then ask me again."
            )
        }

        val image = vision.captureBase64()
            ?: return AgentResult(id, false, "I could not grab a frame from the screen. Try again in a second.")

        val question = utterance.ifBlank { "Describe what is on my screen in two short sentences." }

        return try {
            val response = registry.chat(
                listOf(
                    Message.system(
                        "You are Aria's vision module. Describe only what is visible in the screenshot, " +
                            "be concise because your answer is read aloud, and finish by naming the single " +
                            "most useful next action for the user."
                    ),
                    Message.vision(question, image)
                ),
                ChatOptions(maxTokens = 320, temperature = 0.2f)
            )
            AgentResult(id, true, response.text.ifBlank { "I could not read anything useful from that screen." })
        } catch (t: Throwable) {
            AgentResult(id, false, "Vision analysis failed: ${t.message?.take(160) ?: t.javaClass.simpleName}")
        }
    }
}
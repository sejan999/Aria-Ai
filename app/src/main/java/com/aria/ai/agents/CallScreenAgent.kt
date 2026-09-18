package com.aria.ai.agents

import com.aria.ai.core.system.CallController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telephony specialist: answers, ends and places calls and reports line state.
 * Every platform call and permission check lives in [CallController].
 */
@Singleton
class CallScreenAgent @Inject constructor(
    private val calls: CallController
) : AriaAgent {

    override val id: String = "call_screen"
    override val name: String = "Call Agent"
    override val description: String = "Answers, ends and places calls; reports line state"
    override val keywords: List<String> = listOf(
        "answer the call", "answer call", "pick up", "hang up", "end call", "reject the call",
        "dial", "call ", "who is calling", "ringing", "anyone calling"
    )

    override suspend fun handle(intent: String, utterance: String): AgentResult {
        val heard = utterance.lowercase()

        val message = when {
            heard.contains("hang up") || heard.contains("end call") || heard.contains("reject") ->
                calls.endCall()

            heard.contains("answer") || heard.contains("pick up") -> calls.answerCall()

            heard.contains("who is calling") || heard.contains("ringing") ||
                heard.contains("anyone calling") -> calls.describeState()

            heard.contains("call ") || heard.contains("dial") -> {
                val number = extractNumber(heard)
                if (number == null) {
                    "Tell me the number to dial and I will place the call."
                } else {
                    calls.placeCall(number)
                }
            }

            else -> calls.describeState()
        }

        val success = !message.startsWith("I could not") &&
            !message.startsWith("I need") &&
            !message.startsWith("Ending calls") &&
            !message.startsWith("Answering calls")

        return AgentResult(id, success, message)
    }

    /** Pulls a dialable number out of the utterance. */
    private fun extractNumber(heard: String): String? {
        val match = Regex("(\\+?\\d[\\d\\s\\-]{5,})").find(heard) ?: return null
        return match.groupValues[1].trim()
    }
}
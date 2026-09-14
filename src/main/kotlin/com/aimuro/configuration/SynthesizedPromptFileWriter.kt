package com.aimuro.configuration

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant

// Writes a single, always-overwritten, copy/paste-friendly transcript of the "main-chat" call
// site's synthesized prompt(s) for the most recent /ask request — so it can be pasted into a
// frontier model's chat UI to compare against a local small model's behavior. Debug-profile only:
// override the destination via `app.llm-prompt-log.path` (default logs/synthesized-prompt.txt).
@Component
class SynthesizedPromptFileWriter(
    environment: Environment,
    @Value("\${app.llm-prompt-log.path:logs/synthesized-prompt.txt}") private val path: String,
) {

    private val enabled = environment.activeProfiles.contains("debug")
    private val lock = Any()

    private var seenInstructionCount = 0
    private var roundTripIndex = 0

    fun startNewRequest(userQuery: String) {
        if (!enabled) return
        synchronized(lock) {
            seenInstructionCount = 0
            roundTripIndex = 0
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(
                "=".repeat(80) + "\n" +
                    "NEW REQUEST — ${Instant.now()}\n" +
                    "Question: $userQuery\n" +
                    "=".repeat(80) + "\n\n",
            )
        }
    }

    // previouslySeenCount lets the caller render only the delta since the last round-trip (Spring
    // AI resends the full accumulated message list on every tool-calling loop iteration).
    data class RoundTripStart(val index: Int, val previouslySeenCount: Int)

    fun beginRoundTrip(totalInstructionCount: Int): RoundTripStart {
        if (!enabled) return RoundTripStart(0, totalInstructionCount)
        synchronized(lock) {
            val start = RoundTripStart(++roundTripIndex, seenInstructionCount)
            seenInstructionCount = totalInstructionCount
            return start
        }
    }

    fun appendRoundTrip(renderedBlock: String) {
        if (!enabled) return
        synchronized(lock) {
            File(path).appendText(renderedBlock)
        }
    }

    // The only trustworthy source for the actual final answer: ChatModelObservationContext.response
    // is not reliable per round-trip during a streaming internal-tool-calling loop (see
    // ObservabilityConfiguration.ChatModelIoLoggingHandler), so the caller passes the literal text
    // that was streamed to the client instead of anything derived from the observation API.
    fun finishRequest(finalAnswer: String) {
        if (!enabled) return
        synchronized(lock) {
            File(path).appendText("\n--- FINAL ANSWER (streamed to client) ---\n$finalAnswer\n")
        }
    }
}

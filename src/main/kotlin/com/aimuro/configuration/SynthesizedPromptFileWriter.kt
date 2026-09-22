package com.aimuro.configuration

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Writes a copy/paste-friendly transcript of the "main-chat" call site's synthesized prompt(s) for
// each /ask request — so it can be pasted into a frontier model's chat UI to compare against a
// local small model's behavior. Debug-profile only: each request gets its own file, named by
// inserting a timestamp before the extension of `app.llm-prompt-log.path` (default
// logs/synthesized-prompt.txt -> logs/synthesized-prompt-20260920-114945-176.txt), so earlier
// transcripts are never overwritten.
@Component
class SynthesizedPromptFileWriter(
    environment: Environment,
    @Value("\${app.llm-prompt-log.path:logs/synthesized-prompt.txt}") private val path: String,
) {

    private val enabled = environment.activeProfiles.contains("debug")
    private val lock = Any()

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())

    // File for the request currently in flight; replaced by every startNewRequest().
    private var currentFile = File(path)
    private var seenInstructionCount = 0
    private var roundTripIndex = 0

    fun startNewRequest(userQuery: String) {
        if (!enabled) return
        synchronized(lock) {
            seenInstructionCount = 0
            roundTripIndex = 0
            val base = File(path)
            val file = File(base.parentFile, "${base.nameWithoutExtension}-${timestampFormat.format(Instant.now())}" +
                base.extension.let { if (it.isEmpty()) "" else ".$it" })
            currentFile = file
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
            currentFile.appendText(renderedBlock)
        }
    }

    // Ground truth for the final answer: the caller passes the literal text that was streamed to
    // the client, as a cross-check against the last round-trip block RoundTripLoggingAdvisor wrote
    // (ChatModelObservationContext.response is not reliable per round-trip when streaming — see
    // ObservabilityConfiguration.ChatModelIoLoggingHandler).
    fun finishRequest(finalAnswer: String) {
        if (!enabled) return
        synchronized(lock) {
            currentFile.appendText("\n--- FINAL ANSWER (streamed to client) ---\n$finalAnswer\n")
        }
    }
}

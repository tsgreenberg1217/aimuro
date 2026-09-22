package com.aimuro.configuration

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

// Per-call transcript state for one RulesAgentService.answerRulesQuestion(...) invocation. A fresh
// instance is created for every call (see RulesAgentService) — this is deliberately a plain class,
// not a @Component/singleton bean, because RULES_LOOKUP sub-questions are answered in parallel
// (AgenticChatOrchestrator), and a shared-mutable-state design like SynthesizedPromptFileWriter's
// (one currentFile/seenInstructionCount/roundTripIndex behind a single lock, correct only because
// exactly one main-chat request is ever in flight against it at a time) would let one call's
// round-trip counter or "current file" race with another's. Here, each call owns its own File
// handle and round counter outright, so there is nothing to race: no two threads ever touch the
// same instance.
//
// Writes one transcript file per call under logs/rules/, named from the sanitized question text
// plus a timestamp, so files never collide with each other or with the main logs/synthesized-prompt
// transcripts.
class RulesAgentTranscriptSession(
    question: String,
    private val enabled: Boolean,
    baseDir: File = File("logs/rules"),
) {

    private var seenInstructionCount = 0
    private var roundTripIndex = 0
    private val file: File? = if (enabled) {
        baseDir.mkdirs()
        val name = "${sanitize(question)}-${timestampFormat.format(Instant.now())}-${uniqueSuffix.incrementAndGet()}.txt"
        File(baseDir, name).also {
            it.writeText(
                "=".repeat(80) + "\n" +
                    "RULES AGENT CALL — ${Instant.now()}\n" +
                    "Question: $question\n" +
                    "=".repeat(80) + "\n\n",
            )
        }
    } else null

    // Same delta semantics as SynthesizedPromptFileWriter.beginRoundTrip: previouslySeenCount lets
    // the caller render only the messages new since the prior round-trip. No synchronization needed
    // — this instance is only ever touched by the single (virtual) thread running this one call's
    // tool-calling loop, which is inherently sequential for a single .call().
    data class RoundTripStart(val index: Int, val previouslySeenCount: Int)

    fun beginRoundTrip(totalInstructionCount: Int): RoundTripStart {
        if (!enabled) return RoundTripStart(0, totalInstructionCount)
        val start = RoundTripStart(++roundTripIndex, seenInstructionCount)
        seenInstructionCount = totalInstructionCount
        return start
    }

    fun appendRoundTrip(renderedBlock: String) {
        file?.appendText(renderedBlock)
    }

    fun finish(finalAnswer: String) {
        file?.appendText("\n--- FINAL FINDING ---\n$finalAnswer\n")
    }

    companion object {
        // The only field shared across concurrent sessions — and it's safe by construction: read
        // exactly once per session, at construction, purely to mint a collision-free filename
        // suffix for calls that happen to share both a sanitized question and the same millisecond
        // timestamp. Unlike SynthesizedPromptFileWriter's counters, nothing here needs to stay
        // consistent across a call's own round-trips, so a lock-free AtomicLong is sufficient.
        private val uniqueSuffix = AtomicLong(0)
        private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())

        // Filesystem-safe, readable filename fragment from a sub-question: lowercase, collapse
        // anything non [a-z0-9] into a single '-', trim leading/trailing '-', cap length so a long
        // sub-question doesn't blow past filename limits.
        internal fun sanitize(question: String): String =
            question.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(60)
                .ifEmpty { "question" }
    }
}

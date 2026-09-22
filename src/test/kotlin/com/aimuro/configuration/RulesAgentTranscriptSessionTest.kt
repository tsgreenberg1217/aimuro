package com.aimuro.configuration

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RulesAgentTranscriptSessionTest {

    @Test
    fun `sanitize lowercases and collapses non-alphanumeric runs into single hyphens`() {
        assertEquals(
            "can-a-unit-attack-the-turn-it-s-deployed",
            RulesAgentTranscriptSession.sanitize("Can a unit attack the turn it's deployed?"),
        )
    }

    @Test
    fun `sanitize trims leading and trailing hyphens produced by punctuation`() {
        assertEquals("what-does-suppression-do", RulesAgentTranscriptSession.sanitize("\"What does <Suppression> do?\""))
    }

    @Test
    fun `sanitize truncates long questions to 60 characters`() {
        val longQuestion = "a".repeat(100)
        val result = RulesAgentTranscriptSession.sanitize(longQuestion)
        assertEquals(60, result.length)
        assertEquals("a".repeat(60), result)
    }

    @Test
    fun `sanitize falls back to a placeholder when nothing alphanumeric remains`() {
        assertEquals("question", RulesAgentTranscriptSession.sanitize("???!!!"))
        assertEquals("question", RulesAgentTranscriptSession.sanitize(""))
    }
}

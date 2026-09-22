package com.aimuro.tools

import com.aimuro.configuration.RulesAgentChatClient
import com.aimuro.configuration.RulesAgentRoundTripLoggingAdvisorFactory
import com.aimuro.configuration.RulesAgentTranscriptSession
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.annotation.Tool
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

// Owns the entire process of answering ONE focused rules question: running rulesAgentChatClient's
// own searchRules tool-calling loop (however many round trips that takes, including an implicit
// follow-up search) and returning a single synthesized, evidence-backed finding.
//
// answerRulesQuestion is a genuine @Tool, attached to aimuroChatClient by AgenticChatOrchestrator
// whenever the planner flags needsRulesLookup — exactly like CardToolService's findCard/findCards.
// The main model decides itself whether and how many times to call it; the orchestrator only
// decides whether it's offered at all. The <tool_routing> block (see system-prompt.md's Tool
// Routing Rule) gives it the planner's exact sub-question text to pass through unchanged for its
// first call(s) per request; any further calls are the model's own follow-up research.
@Service
class RulesAgentService(
    @RulesAgentChatClient private val rulesAgentChatClient: ChatClient,
    private val loggingAdvisorFactory: RulesAgentRoundTripLoggingAdvisorFactory,
    environment: Environment,
) {

    private val logger = LoggerFactory.getLogger(RulesAgentService::class.java)

    // Same env-gating idiom as SynthesizedPromptFileWriter: transcripts only under `debug`.
    private val transcriptsEnabled = environment.activeProfiles.contains("debug")

    @Tool(description = "Research a Gundam TCG rules question — including any necessary follow-up searches " +
        "to resolve exceptions or referenced terms — and return one synthesized, evidence-backed finding. " +
        "If the request includes routing instructions that give an exact question string, pass that string " +
        "unchanged as the question. Otherwise, formulate a focused, self-contained question describing the " +
        "exact rule, concept, or interaction you need — do not just pass the user's raw message verbatim if " +
        "it contains unrelated context. Only ask about terms that appear in the question, retrieved card " +
        "data, or a previously returned finding; never invent keyword names.")
    fun answerRulesQuestion(question: String): String {
        val session = RulesAgentTranscriptSession(question, enabled = transcriptsEnabled)
        val loggingAdvisor = loggingAdvisorFactory.create(session)

        return try {
            logger.info("RulesAgentService: researching '{}'", question)
            val answer = rulesAgentChatClient
                .prompt()
                .user(question)
                .advisors(loggingAdvisor) // request-level only — fresh advisor instance per call, no shared state
                .call()
                .content()
                .orEmpty()
            session.finish(answer)
            logger.info("RulesAgentService: finding for '{}': {}", question, answer)
            answer.ifBlank { fallbackFinding(question, "The rules agent returned an empty response.") }
        } catch (e: Exception) {
            logger.error("RulesAgentService: research failed for '{}'", question, e)
            session.finish("<error>${e.message}</error>")
            fallbackFinding(question, "An error occurred while researching this rule: ${e.message}")
        }
    }

    // Fail-open shape: still a well-formed <rules_finding> so the main model's prompt structure
    // never breaks even when the sub-agent call itself throws or returns nothing.
    private fun fallbackFinding(question: String, reason: String): String =
        "<rules_finding>\n<question>$question</question>\n<answer>\n$reason\n</answer>\n</rules_finding>"
}

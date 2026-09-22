package com.aimuro.configuration

import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain

// Per-call analogue of RoundTripLoggingAdvisor, for the rules agent's own internal searchRules
// tool-calling loop. A new instance is created for every RulesAgentService.answerRulesQuestion(...)
// call (via RulesAgentRoundTripLoggingAdvisorFactory), bound to that call's own
// RulesAgentTranscriptSession, and attached via request-level .advisors(...) on that one .prompt()
// call — never .defaultAdvisors(...) on the shared rulesAgentChatClient bean. That's what makes
// this safe under N parallel rules-agent calls: each call's advisor instance has its own session
// with private fields, so there is no shared mutable state between concurrent calls at all
// (contrast with SynthesizedPromptFileWriter, a singleton with one shared mutable "current
// file"/round-index, correct only for one in-flight main-chat request at a time).
//
// Uses the CallAdvisor side of ToolCallingAdvisor (which implements both CallAdvisor and
// StreamAdvisor — confirmed via the Spring AI 2.0.1 jar) since RulesAgentService uses .call(): a
// simple blocking result per parallel task, run on its own virtual thread. Order must be strictly
// greater than ToolCallingAdvisor.DEFAULT_ORDER so this advisor re-enters once per round-trip of
// the inner searchRules loop, exactly like RoundTripLoggingAdvisor does for the main-chat loop.
class RulesAgentRoundTripLoggingAdvisor(
    private val session: RulesAgentTranscriptSession,
) : CallAdvisor {

    override fun getName(): String = "RulesAgentRoundTripLoggingAdvisor"

    override fun getOrder(): Int = ToolCallingAdvisor.DEFAULT_ORDER + 100

    override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse {
        val instructions = request.prompt().instructions
        val (index, previouslySeenCount) = session.beginRoundTrip(instructions.size)
        val newMessages = instructions.drop(previouslySeenCount)

        val response = chain.nextCall(request)
        session.appendRoundTrip(renderRound(index, newMessages, response.chatResponse(), label = "rules-agent"))
        return response
    }
}

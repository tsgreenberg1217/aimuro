package com.aimuro.configuration

import org.springframework.stereotype.Component

// Stateless factory so RulesAgentService gets RulesAgentRoundTripLoggingAdvisor via constructor
// injection rather than calling its constructor directly with `new`. The factory itself holds no
// per-call state — it's an ordinary singleton bean — so injecting it changes nothing about the
// concurrency design: each RulesAgentService.answerRulesQuestion(...) call still asks for (and
// gets) a fresh advisor instance bound to its own fresh RulesAgentTranscriptSession.
@Component
class RulesAgentRoundTripLoggingAdvisorFactory {
    fun create(session: RulesAgentTranscriptSession): RulesAgentRoundTripLoggingAdvisor =
        RulesAgentRoundTripLoggingAdvisor(session)
}

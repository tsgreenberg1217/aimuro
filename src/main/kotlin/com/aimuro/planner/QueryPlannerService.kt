package com.aimuro.planner

import com.aimuro.configuration.PlannerChatClient
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

@Service
class QueryPlannerService(
    @PlannerChatClient private val plannerChatClient: ChatClient,
) {

    private val logger = LoggerFactory.getLogger(QueryPlannerService::class.java)

    // Fail-open default: if the planner call throws or the model's output can't be parsed
    // into a QueryPlan, degrade to "call both tools" (the old always-on behavior) rather
    // than silently skipping a lookup the user actually needed. No real sub-question split
    // is possible here, so both tags get the full raw query as their routing hint — which
    // degrades to the pre-hint behavior of the model working from the raw query itself.
    private fun failOpenPlan(query: String) = QueryPlan(
        subQuestions = listOf(
            SubQuestion(query, ToolTarget.CARD_LOOKUP),
            SubQuestion(query, ToolTarget.RULES_LOOKUP),
        ),
        needsRulesLookup = true,
        needsCardLookup = true,
    )

    fun plan(query: String): QueryPlan {
        val plan = try {
            // .entity(QueryPlan::class.java) is Spring AI's structured-output support: it appends
            // a JSON-schema instruction (derived from QueryPlan's fields) to the prompt via
            // BeanOutputConverter, then parses the model's JSON response back into a QueryPlan
            // with Jackson (jackson-module-kotlin makes this work for a Kotlin data class).
            // See: org.springframework.ai.converter.BeanOutputConverter.
            plannerChatClient
                .prompt()
                .user(query)
                .call()
                .entity(QueryPlan::class.java)
        } catch (e: Exception) {
            logger.warn("QueryPlannerService: planning failed for '{}', falling back to fail-open plan", query, e)
            null
        } ?: failOpenPlan(query)

        logger.info("QueryPlan for '{}': {}", query, plan)
        return plan
    }
}

package com.aimuro.planner

enum class SearchDepth { SIMPLE, MODERATE, IN_DEPTH }

enum class ToolTarget { CARD_LOOKUP, RULES_LOOKUP, NONE }

// question is used verbatim as the routing hint for whichever tool it's tagged with —
// see AgenticChatOrchestrator.
data class SubQuestion(
    val question: String,
    val tool: ToolTarget = ToolTarget.NONE,
)

// Emitted by QueryPlannerService.
data class QueryPlan(
    val subQuestions: List<SubQuestion> = emptyList(),
    val needsRulesLookup: Boolean = false,
    val needsCardLookup: Boolean = false,
    val depth: SearchDepth = SearchDepth.MODERATE,
)

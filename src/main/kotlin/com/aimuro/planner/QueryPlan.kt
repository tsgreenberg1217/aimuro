package com.aimuro.planner

enum class SearchDepth { SIMPLE, MODERATE, IN_DEPTH }

// Emitted by QueryPlannerService. subQuestions is logged for observability only —
// deliberately not injected back into the prompt (see QueryPlannerService).
data class QueryPlan(
    val subQuestions: List<String> = emptyList(),
    val needsRulesLookup: Boolean = false,
    val needsCardLookup: Boolean = false,
    val depth: SearchDepth = SearchDepth.MODERATE,
)

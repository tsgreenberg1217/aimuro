package com.aimuro.tools

import com.aimuro.configuration.ComplexityChatClient
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

// Complexity of a rules question. Owned by the rules tool: it alone decides how wide a net to
// cast (top-K) — neither the planner nor the main model supplies it.
enum class SearchDepth { SIMPLE, MODERATE, IN_DEPTH }

// Wrapper so Spring AI's BeanOutputConverter has an object schema to target rather than a bare enum.
data class ComplexityAssessment(val depth: SearchDepth = SearchDepth.MODERATE)

@Service
class RulesComplexityClassifier(
    @ComplexityChatClient private val complexityChatClient: ChatClient,
) {

    private val logger = LoggerFactory.getLogger(RulesComplexityClassifier::class.java)

    // Fail-open to MODERATE (the middle top-K) if the call throws or can't be parsed, rather
    // than failing the search.
    fun classify(query: String): SearchDepth {
        val depth = try {
            complexityChatClient
                .prompt()
                .user(query)
                .call()
                .entity(ComplexityAssessment::class.java)
                ?.depth
        } catch (e: Exception) {
            logger.warn("RulesComplexityClassifier: classification failed for '{}', defaulting to MODERATE", query, e)
            null
        } ?: SearchDepth.MODERATE

        logger.info("Rules complexity for '{}': {}", query, depth)
        return depth
    }
}

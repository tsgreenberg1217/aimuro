package com.aimuro.tools

import com.aimuro.planner.SearchDepth
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class RulesSearchToolService(
    private val vectorStore: VectorStore,
    @Value("\${app.ai.similarity-threshold:0.6}") private val similarityThreshold: Double,
    @Value("\${app.ai.nomic-prefix:false}") private val nomicPrefix: Boolean,
) {

    private val logger = LoggerFactory.getLogger(RulesSearchToolService::class.java)

    private val topKByDepth = mapOf(
        SearchDepth.SIMPLE to 10,
        SearchDepth.MODERATE to 16,
        SearchDepth.IN_DEPTH to 20,
    )

    @Tool(description = "Search the Gundam TCG comprehensive rules for passages relevant to a specific rules question. " +
        "Formulate a focused, self-contained search query describing the exact rule, concept, or interaction you need — " +
        "do not just pass the user's raw message verbatim if it contains unrelated context. " +
        "Set depth=SIMPLE for a basic factual lookup, MODERATE for a question needing some surrounding rule context, " +
        "IN_DEPTH for a complex multi-rule interaction requiring broader context.")
    fun searchRules(query: String, depth: SearchDepth = SearchDepth.MODERATE): String {
        val topK = topKByDepth.getValue(depth)
        logger.info("searchRules called with query='{}', depth={} (topK={})", query, depth, topK)

        val docs = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(if (nomicPrefix) "search_query: $query" else query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build()
        ) ?: emptyList()

        docs.forEachIndexed { i, doc ->
            logger.info("  [$i] score=${doc.score} title=${doc.metadata["title"]}")
        }

        val response = if (docs.isEmpty()) "No relevant rules passages found for: $query"
        else docs.joinToString("\n\n") { it.text.orEmpty() }

        logger.info("searchRules response ({} chars): {}", response.length, response.take(300))
        return response
    }
}

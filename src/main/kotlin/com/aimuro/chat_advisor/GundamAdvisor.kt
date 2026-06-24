package com.aimuro.chat_advisor

import com.aimuro.configuration.prompt.PromptConfig
import com.aimuro.tools.rulesData
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore

class GundamAdvisor(
    private val chatModel: ChatModel,
    private val vectorStore: VectorStore,
    private val promptConfig: PromptConfig,
    private val similarityThreshold: Double = 0.6,
    private val nomicPrefix: Boolean = false
) : BaseAdvisor {

    private val logger = LoggerFactory.getLogger(GundamAdvisor::class.java)

    private val simpleK = 10
    private val moderateK = 16
    private val inDepthK = 20

    override fun before(chatClientRequest: ChatClientRequest, advisorChain: AdvisorChain): ChatClientRequest {
        // Strip card data appended by CardServiceAdvisor so the vector search uses only the user's question.
        val question = chatClientRequest.prompt().userMessage.text

        logger.info("GundamAdvisor: Processing question: $question")

        val k = classifyQuestionDepth(question)

        val docs = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(if (nomicPrefix) "search_query: $question" else question)
                .topK(k)
                .similarityThreshold(similarityThreshold)
                .build()
        ) ?: emptyList()

        logger.info("GundamAdvisor: Retrieved ${docs.size} docs (K=$k, threshold=$similarityThreshold)")
        docs.forEachIndexed { i, doc ->
            logger.info("  [$i] score=${doc.score} title=${doc.metadata["title"]}")
        }

        val context = docs.joinToString("\n\n") { it.text.orEmpty() }

        return chatClientRequest.mutate()
            .context(rulesData, context)
            .build()
    }

    override fun after(
        chatClientResponse: ChatClientResponse,
        advisorChain: AdvisorChain
    ): ChatClientResponse = chatClientResponse

    private fun classifyQuestionDepth(query: String): Int {
        val response = chatModel.call(promptConfig.classificationPrompt(query))
        val classification = response.result?.output?.text?.trim()?.uppercase() ?: "MODERATE"
        val k = when {
            classification.contains("SIMPLE") -> simpleK
            classification.contains("IN_DEPTH") || classification.contains("INDEPTH") -> inDepthK
            else -> moderateK
        }
        logger.info("GundamAdvisor: Classified as '$classification', using K=$k")
        return k
    }

    override fun getOrder(): Int = BaseAdvisor.HIGHEST_PRECEDENCE + 1
}

package com.aimuro.aimuro

import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore

class GundamAdvisor(
    private val advisor: QuestionAnswerAdvisor,
    private val chatModel: ChatModel,
    private val vectorStore: VectorStore,
    private val promptTemplate: PromptTemplate,
    private val similarityThreshold: Double = 0.6
) : BaseAdvisor by advisor {

    private val simpleK = 6
    private val moderateK = 10
    private val inDepthK = 16


    override fun before(chatClientRequest: ChatClientRequest, advisorChain: AdvisorChain): ChatClientRequest {
        val query = extractUserQuery(chatClientRequest)
        val k = if (query != null) classifyQuestionDepth(query) else moderateK

        val dynamicAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(
                SearchRequest.builder()
                    .topK(k)
                    .similarityThreshold(similarityThreshold)
                    .build()
            )
            .promptTemplate(promptTemplate)
            .build()

        val mutatedRequest = dynamicAdvisor.before(chatClientRequest, advisorChain)
        println("GundamAdvisor: Before processing request with K=$k: ${mutatedRequest.prompt}")
        return mutatedRequest
    }

    private fun extractUserQuery(chatClientRequest: ChatClientRequest): String? =
        chatClientRequest.prompt().instructions
            .filterIsInstance<UserMessage>()
            .lastOrNull()
            ?.text

    private fun classifyQuestionDepth(query: String): Int {
        val classificationPrompt = Prompt(
            "Classify the depth of this Gundam TCG rules question. " +
                    "Reply with ONLY one word — SIMPLE, MODERATE, or IN_DEPTH. " +
                    "SIMPLE = basic factual lookup. MODERATE = requires some rule knowledge. IN_DEPTH = complex multi-rule interaction. " +
                    "Question: $query"
        )
        val response = chatModel.call(classificationPrompt)
        val classification = response.result?.output?.text?.trim()?.uppercase() ?: "MODERATE"
        val k = when {
            classification.contains("SIMPLE") -> simpleK
            classification.contains("IN_DEPTH") || classification.contains("INDEPTH") -> inDepthK
            else -> moderateK
        }
        println("GundamAdvisor: Classified as '$classification', using K=$k")
        return k
    }
}

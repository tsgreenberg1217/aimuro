package com.aimuro.chat_advisor

import com.aimuro.configuration.CardServiceLLMToolClient
import com.aimuro.configuration.prompt.PromptConfig
import com.aimuro.tools.CardDataKey
import com.aimuro.tools.CardToolCapture
import com.aimuro.tools.CardToolService
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.ai.chat.model.ChatModel


class CardServiceAdvisor(
    @CardServiceLLMToolClient private val cardServiceLLMToolClient: ChatClient,
    private val cardToolService: CardToolService,
    private val chatModel: ChatModel,
    private val promptConfig: PromptConfig
) : BaseAdvisor {

    private val logger = LoggerFactory.getLogger(CardServiceAdvisor::class.java)

    override fun getOrder(): Int = BaseAdvisor.HIGHEST_PRECEDENCE

    override fun before(request: ChatClientRequest, advisorChain: AdvisorChain): ChatClientRequest {
        logger.info("calling card enrichment advisor for request: ${request.prompt().userMessage.text}")
        val query = request.prompt().userMessage.text

        val cardData = fetchCardData(query) ?: return request



        val enrichedPrompt = enrichPrompt(query, cardData)

        logger.info("CardEnrichmentAdvisor: $enrichedPrompt")
        return request.mutate()
            .prompt(request.prompt().augmentUserMessage(enrichedPrompt))
            .context(CardDataKey, cardData)
            .build()
    }

    override fun after(response: ChatClientResponse, advisorChain: AdvisorChain): ChatClientResponse = response

    private fun fetchCardData(query: String): String? {
        val capture = CardToolCapture(cardToolService)
        cardServiceLLMToolClient
            .prompt()
            .user("Question: $query")
            .tools(capture)
            .call()
            .content()

        logger.info("CardEnrichmentAdvisor: result='{}'", capture.results)
        if (capture.results.isEmpty()) return null

        val combined = capture.results.joinToString("\n\n")
        logger.info("CardEnrichmentAdvisor: pre-flight response='{}'", combined)
        return combined
    }



    private fun enrichPrompt(query: String, cardData:String): String {
        return chatModel.call(promptConfig.cardEnrichmentPrompt(query, cardData)).result?.output?.text?.trim() ?: query
    }
}

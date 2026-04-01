package com.aimuro.chat_advisor

import com.aimuro.configuration.CardServiceLLMToolClient
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor


class CardServiceAdvisor(
    @CardServiceLLMToolClient private val cardServiceLLMToolClient: ChatClient,
) : BaseAdvisor {

    private val logger = LoggerFactory.getLogger(CardServiceAdvisor::class.java)

    override fun getOrder(): Int = BaseAdvisor.HIGHEST_PRECEDENCE

    override fun before(request: ChatClientRequest, advisorChain: AdvisorChain): ChatClientRequest {
        val query = request.prompt().userMessage.text

        val cardData = fetchCardData(query) ?: return request

        logger.info("CardEnrichmentAdvisor: enriching request with card data")
        return request.mutate()
            .prompt(request.prompt().augmentUserMessage("\n\n[Relevant Card Data]\n$cardData"))
            .build()
    }

    override fun after(response: ChatClientResponse, advisorChain: AdvisorChain): ChatClientResponse = response

    private fun fetchCardData(query: String): String? {
        val response = cardServiceLLMToolClient
            .prompt()
            .user(
                "If the following Gundam TCG question requires looking up specific card data, " +
                        "use the available tools to fetch it and return only the card data. " +
                        "DO NOT TRY TO ANSWER THE QUESTION OR INFER ANYTHING YET!" +
                        "If no card lookup is needed, reply only with: NONE\n\nQuestion: $query"
            )
            .call()
            .content() ?: return null

        logger.info("CardEnrichmentAdvisor: pre-flight response='{}'", response)

        return response.takeUnless { it.trim().equals("NONE", ignoreCase = true) }
    }
}

package com.aimuro.chat_advisor

import com.aimuro.configuration.prompt.PromptConfig
import com.aimuro.tools.CardDataKey
import com.aimuro.tools.rulesData
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor

class PromptAssemblerAdvisor(private val promptConfig: PromptConfig) : BaseAdvisor {

    private val logger = LoggerFactory.getLogger(PromptAssemblerAdvisor::class.java)

    override fun before(request: ChatClientRequest, advisorChain: AdvisorChain): ChatClientRequest {
        val question = request.prompt().userMessage.text
        val rulesContext = request.context()[rulesData] as? String ?: ""
        val cardData = request.context()[CardDataKey] as? String ?: ""

        val rendered = promptConfig.rulesAdvisorPromptTemplate
            .render(mapOf(
                "query" to question,
                "card_data" to cardData,
                "question_answer_context" to rulesContext
            ))

        logger.info("PromptAssemblerAdvisor: final user message:\n$rendered")

        return request.mutate()
            .prompt(request.prompt().augmentUserMessage(rendered))
            .build()
    }

    override fun after(response: ChatClientResponse, advisorChain: AdvisorChain): ChatClientResponse = response

    override fun getOrder(): Int = BaseAdvisor.HIGHEST_PRECEDENCE + 2
}

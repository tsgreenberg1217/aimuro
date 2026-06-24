package com.aimuro.configuration.prompt

import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.template.st.StTemplateRenderer
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class DefaultPromptConfig : PromptConfig {

    override val systemPrompt: String by lazy {
        readPromptResource("prompts/system-prompt.txt")
    }

    override val rulesAdvisorPromptTemplate: PromptTemplate by lazy {
        PromptTemplate.builder()
            .renderer(
                StTemplateRenderer.builder()
                    .startDelimiterToken('<')
                    .endDelimiterToken('>')
                    .build()
            )
            .template(readPromptResource("prompts/rules-advisor-template.txt"))
            .build()
    }

    override val cardServiceSystemPrompt: String by lazy {
        readPromptResource("prompts/card-service-system-prompt.txt")
    }

    private val classificationSystemMessage: String by lazy {
        readPromptResource("prompts/classification-system-message.txt")
    }

    private val classificationUserMessageTemplate: String by lazy {
        readPromptResource("prompts/classification-user-message-template.txt")
    }

    private val cardEnrichmentSystemMessage: String by lazy {
        readPromptResource("prompts/card-enrichment-system-message.txt")
    }

    private val cardEnrichmentUserMessageTemplate: String by lazy {
        readPromptResource("prompts/card-enrichment-user-message-template.txt")
    }

    override fun classificationPrompt(query: String): Prompt = Prompt(
        listOf(
            SystemMessage(classificationSystemMessage),
            UserMessage(
                classificationUserMessageTemplate.replace("{{query}}", query)
            )
        )
    )

    override fun cardEnrichmentPrompt(
        query: String,
        cardData: String
    ): Prompt = Prompt(
        listOf(
            SystemMessage(cardEnrichmentSystemMessage),
            UserMessage(
                cardEnrichmentUserMessageTemplate
                    .replace("{{query}}", query)
                    .replace("{{cardData}}", cardData)
            )
        )
    )

    private fun readPromptResource(path: String): String =
        ClassPathResource(path).getContentAsString(Charsets.UTF_8).trim()
}

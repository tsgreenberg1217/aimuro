package com.aimuro.configuration.prompt

import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.prompt.PromptTemplate

interface PromptConfig {
    val systemPrompt: String
    val rulesAdvisorPromptTemplate: PromptTemplate
    val cardServiceSystemPrompt: String
    fun classificationPrompt(query: String): Prompt
    fun cardEnrichmentPrompt(query: String, cardData: String): Prompt
}

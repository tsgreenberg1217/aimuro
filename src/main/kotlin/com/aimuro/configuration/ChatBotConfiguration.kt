package com.aimuro.configuration

import com.aimuro.chat_advisor.CardServiceAdvisor
import com.aimuro.chat_advisor.GundamAdvisor
import com.aimuro.chat_advisor.PromptAssemblerAdvisor
import com.aimuro.configuration.prompt.PromptConfig
import com.aimuro.tools.CardToolService
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Qualifier
annotation class ComprehensiveRulesAdvisor

@Qualifier
annotation class CardEnrichmentAdvisor

@Qualifier
annotation class PromptAssemblerAdvisorBean

@Qualifier
annotation class CardServiceLLMToolClient

@Configuration
class ChatBotConfiguration {

    @Bean
    @ComprehensiveRulesAdvisor
    fun getComprehensiveRulesAdvisor(
        vectorStore: VectorStore,
        chatModel: ChatModel,
        promptConfig: PromptConfig,
    ): BaseAdvisor = GundamAdvisor(chatModel, vectorStore, promptConfig, 0.6)

    @Bean
    @CardServiceLLMToolClient
    fun cardServiceLLMToolClient(
        chatClientBuilder: ChatClient.Builder,
        promptConfig: PromptConfig
    ): ChatClient = chatClientBuilder
        .defaultSystem(promptConfig.cardServiceSystemPrompt)
        .build()

    @Bean
    @CardEnrichmentAdvisor
    fun cardEnrichmentAdvisor(
        @CardServiceLLMToolClient cardServiceLLMToolClient: ChatClient,
        cardToolService: CardToolService,
        chatModel: ChatModel,
        promptConfig: PromptConfig
    ): BaseAdvisor = CardServiceAdvisor(cardServiceLLMToolClient, cardToolService, chatModel, promptConfig)

    @Bean
    @PromptAssemblerAdvisorBean
    fun promptAssemblerAdvisor(promptConfig: PromptConfig): BaseAdvisor = PromptAssemblerAdvisor(promptConfig)

    @Bean
    @Primary
    fun aimuroChatClient(
        chatClientBuilder: ChatClient.Builder,
        @CardEnrichmentAdvisor cardEnrichmentAdvisor: BaseAdvisor,
        @ComprehensiveRulesAdvisor comprehensiveRulesAdvisor: BaseAdvisor,
        @PromptAssemblerAdvisorBean promptAssemblerAdvisor: BaseAdvisor,
        promptConfig: PromptConfig
    ): ChatClient {
        return chatClientBuilder
            .defaultSystem(promptConfig.systemPrompt)
            .defaultAdvisors(
                cardEnrichmentAdvisor,
                comprehensiveRulesAdvisor,
                promptAssemblerAdvisor,
            )
            .build()
    }
}

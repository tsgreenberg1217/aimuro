package com.aimuro.aimuro.configuration

import com.aimuro.aimuro.chat_advisor.CardServiceAdvisor
import com.aimuro.aimuro.chat_advisor.GundamAdvisor
import com.aimuro.aimuro.tools.CardToolService
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary


val promptTemplate = PromptTemplate(
    "{query}\n\nYou are a friendly anime robot who is an expert on the Gundam Trading card game. " +
            "Given the context and provided history information and not prior knowledge,\nreply to the user comment. " +
            "Dont overwhelm the user with too much information unless they ask for a more detailed answer. " +
            "Look for explicit text that says it requires conditions. If no such text is found in the retrieved excerpts, do not assume it is prohibited by default. " +
            "If the answer is not in the context, inform\nthe user that you can't answer the question.\n"
)


val generalPromptTemplate = PromptTemplate(
    "{query}\n\nUse this as general instructions. " +
            "---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\n"
)

val rulesAdvisorPrompt = PromptTemplate(
    "Use the question and any supplied card data below to query the Gundam TCG rules database. " +
            "The retrieved rules excerpts should be relevant to both the question being asked and any card information provided. " +
            "If card data is present, factor in that card's specific attributes (type, level, cost, color, traits, effect, etc) when determining which rules apply.\n\n" +
            "{query}\n\n" +
            "---------------------\n" +
            "Rules Context:\n" +
            "{question_answer_context}\n" +
            "---------------------\n\n"
)

@Qualifier
annotation class ComprehensiveRulesAdvisor

@Qualifier
annotation class CardEnrichmentAdvisor

@Qualifier
annotation class CardServiceLLMToolClient

@Configuration
class ChatBotConfiguration {

    @Bean
    @ComprehensiveRulesAdvisor
    fun getComprehensiveRulesAdvisor(
        vectorStore: VectorStore,
        chatModel: ChatModel
    ): BaseAdvisor = GundamAdvisor(chatModel, vectorStore, rulesAdvisorPrompt)

    @Bean
    @CardServiceLLMToolClient
    fun cardServiceLLMToolClient(
        chatClientBuilder: ChatClient.Builder,
        cardToolService: CardToolService
    ): ChatClient = chatClientBuilder
        .defaultTools(cardToolService)
        .build()

    @Bean
    @CardEnrichmentAdvisor
    fun cardEnrichmentAdvisor(
        @CardServiceLLMToolClient cardServiceLLMToolClient: ChatClient
    ): BaseAdvisor = CardServiceAdvisor(cardServiceLLMToolClient)

    @Bean
    @Primary
    fun aimuroChatClient(
        chatClientBuilder: ChatClient.Builder,
        @CardEnrichmentAdvisor cardEnrichmentAdvisor: BaseAdvisor,
        @ComprehensiveRulesAdvisor smallComprehensiveRulesAdvisor: BaseAdvisor,
    ): ChatClient {
        return chatClientBuilder
            .defaultAdvisors(
                cardEnrichmentAdvisor,
                smallComprehensiveRulesAdvisor,
            )
            .build()
    }
}
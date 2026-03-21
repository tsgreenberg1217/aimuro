package com.aimuro.aimuro.configuration

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


val promptTemplate = PromptTemplate(
    "{query}\n\nYou are a friendly anime robot who is an expert on the Gundam Trading card game. " +
            "Given the context and provided history information and not prior knowledge,\nreply to the user comment. " +
            "Dont overwhelm the user with too much information unless they ask for a more detailed answer. " +
            "Look for explicit text that says it requires conditions. If no such text is found in the retrieved excerpts, do not assume it is prohibited by default. " +
            "If the answer is not in the context, inform\nthe user that you can't answer the question.\n" +
            "If the user mentions a specific card name or asks about a category of cards, use the available tools to look up card data before answering.\n"
)


val generalPromptTemplate = PromptTemplate(
    "{query}\n\nUse this as general instructions. " +
            "---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\n"
)

val compPromptTemplate = PromptTemplate(
    "{query}\n\nUse this information as well. " +
            "---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\n"
)

@Qualifier
annotation class ComprehensiveRulesAdvisor

@Qualifier
annotation class DefaultComprehensiveRulesAdvisor

@Configuration
class ChatBotConfiguration {

    @Bean
    @DefaultComprehensiveRulesAdvisor
    fun getDefaultAdvisor(vectorStore: VectorStore): QuestionAnswerAdvisor =
        QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(
                SearchRequest.builder()
                    .topK(4)
                    .similarityThreshold(.75)
                    .build()
            )
            .promptTemplate(generalPromptTemplate)
            .build()

    @Bean
    @ComprehensiveRulesAdvisor
    fun getComprehensiveRulesAdvisor(
        @DefaultComprehensiveRulesAdvisor defaultAdvisor: QuestionAnswerAdvisor,
        vectorStore: VectorStore,
        chatModel: ChatModel
    ): BaseAdvisor = GundamAdvisor(defaultAdvisor, chatModel, vectorStore, generalPromptTemplate)

    @Bean
    fun aimuroChatClient(
        chatClientBuilder: ChatClient.Builder,
        @ComprehensiveRulesAdvisor smallComprehensiveRulesAdvisor: BaseAdvisor,
        cardToolService: CardToolService
    ): ChatClient {
        return chatClientBuilder
            .defaultAdvisors(
                smallComprehensiveRulesAdvisor,
            )
            .defaultTools(cardToolService)
            .build()
    }
}
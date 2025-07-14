package com.aimuro.aimuro

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


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

val compPromptTemplate = PromptTemplate(
    "{query}\n\nUse this information as well. " +
            "---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\n"
)

@Qualifier
annotation class ComprehensiveRulesAdvisor
@Qualifier
annotation class SmallComprehensiveRulesAdvisor

@Qualifier
annotation class WebRulesAdvisor

@Configuration
class ChatBotConfiguration {
    @Bean
    @WebRulesAdvisor
    fun getQuestionAnswerAdvisor(vectorStore: PgVectorStore): BaseAdvisor {
        val advisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(
                SearchRequest.builder()
                    .filterExpression("detail_level == 'general'")
                    .topK(2)
                    .build()
            )
            .promptTemplate(generalPromptTemplate)
            .build()
        return GundamAdvisor(advisor)
    }


    @Bean
    @ComprehensiveRulesAdvisor
    fun getCompRulesAdvicor(vectorStore: PgVectorStore): BaseAdvisor {
        val advisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(
                SearchRequest.builder()
                    .filterExpression("detail_level == 'in-depth'")
                    .build()
            )
            .promptTemplate(compPromptTemplate)
            .build()
        return GundamAdvisor(advisor)
    }

    @Bean
    @SmallComprehensiveRulesAdvisor
    fun getSmallCompRulesAdvicor(vectorStore: PgVectorStore): BaseAdvisor {
        val advisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(
                SearchRequest.builder()
                    .filterExpression("detail_level == 'in-depth-small'")
                    .topK(16)
                    .similarityThreshold(.75)
                    .build()
            )
            .promptTemplate(compPromptTemplate)
            .build()
        return GundamAdvisor(advisor)
    }

    @Bean
    fun aimuroChatClient(
        chatClientBuilder: ChatClient.Builder,
        @WebRulesAdvisor webRulesAdvisor:  BaseAdvisor,
        @ComprehensiveRulesAdvisor comprehensiveRulesAdvisor: BaseAdvisor,
        @SmallComprehensiveRulesAdvisor smallcomprehensiveRulesAdvisor: BaseAdvisor
    ): ChatClient {
        return chatClientBuilder
            .defaultAdvisors(
                smallcomprehensiveRulesAdvisor,
                comprehensiveRulesAdvisor,
                webRulesAdvisor
            )
            .build()
    }
}
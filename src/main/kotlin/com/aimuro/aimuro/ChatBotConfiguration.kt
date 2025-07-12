package com.aimuro.aimuro

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Qualifier
annotation class RulesAdvisor

@Qualifier
annotation class WebRulesAdvisor

@Configuration
class ChatBotConfiguration {
    @Bean
    fun getQuestionAnswerAdvisor(vectorStore: PgVectorStore): QuestionAnswerAdvisor {
        val promptTemplate = PromptTemplate(
            "{query}\n\nYou are a friendly anime robot who is an expert on the Gundam Trading card game. " +
                    "Relevant information about the game is surrounded by ---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\nGiven the context and provided history information and not prior knowledge,\nreply to the user comment. " +
                    "Dont overwhelm the user with too much information unless they ask for a more detailed answer. " +
                    "Look for explicit text that says it requires conditions. If no such text is found in the retrieved excerpts, do not assume it is prohibited by default. " +
                    "If the answer is not in the context, inform\nthe user that you can't answer the question.\n"
        )
        val advisor = QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(
                SearchRequest.builder()
//                    .similarityThreshold(0.75)
//                    .topK(8)
                    .build()
            )
            .promptTemplate(promptTemplate)
            .build()
        return advisor
    }

    @Bean
    fun aimuroChatClient(chatClientBuilder: ChatClient.Builder, gundamAdvisor: GundamAdvisor): ChatClient {
        return chatClientBuilder
            .defaultAdvisors(gundamAdvisor)
            .build()
    }
}
package com.aimuro.aimuro

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController


data class RulesQuestion(
    val question: String
)

@RestController
class ChatController(
    private val vectorStore: PgVectorStore,
    private val chatClientBuilder: ChatClient.Builder
) {

    lateinit var chatClient: ChatClient

    init {
        chatClient = chatClientBuilder
            .defaultAdvisors(QuestionAnswerAdvisor(vectorStore))
            .build()
    }


    @PostMapping("/rules")
    fun getRules(@RequestBody rulesQuestion: RulesQuestion): String {
        val response = chatClient
            .prompt()
            .user(rulesQuestion.question).call().content() ?: "Hello Todd :)"
        return response

    }

}
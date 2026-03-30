package com.aimuro.aimuro.controller

import com.aimuro.aimuro.configuration.promptTemplate
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux


data class ChatRequest(
    val conversation: List<ChatMessage>
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class RulesResponse(
    val answer: String
)

@RestController
class ChatController(
    private val chatClient: ChatClient
) {

    @PostMapping("/askNoStream")
    fun ask(@RequestBody request: ChatRequest): RulesResponse = sendAimuroRequest(request)
        .call()
        .run { content() ?: "Try asking the question in a different way" }
        .run(::RulesResponse)

    @PostMapping("/ask")
    fun getRulesStream(@RequestBody request: ChatRequest): Flux<RulesResponse> = sendAimuroRequest(request)
        .stream()
        .content()
        .map(::RulesResponse)


    private fun sendAimuroRequest(request: ChatRequest) = chatClient
        .prompt(promptTemplate.template)
        .messages(
            extractMessagesFromConversation(request.conversation).also { list ->
                list.forEach { println("previous message for type ${it.messageType}: ${it.text}") }
            }
        )
        .user(request.conversation.last().content)


    private fun extractMessagesFromConversation(conversation: List<ChatMessage>): List<Message> {
        return conversation.map { chatMessage ->
            if (chatMessage.role == "user") {
                UserMessage(chatMessage.content)
            } else {
                AssistantMessage(chatMessage.content)
            }
        }
    }


}
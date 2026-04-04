package com.aimuro.controller

import com.aimuro.history.ChatResponse
import com.aimuro.service.AimuroChatService
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

data class ChatRequest(
    val conversation: List<ChatMessage>,
    val conversationId: Long
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class RulesResponse(
    val answer: String,
    val isComplete: Boolean = true
)

@RestController
class ChatController(private val aimuroChatService: AimuroChatService) {

    companion object{
        private val logger = LoggerFactory.getLogger(ChatController::class.java)
    }

    @PostMapping("/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun ask(@RequestBody request: ChatRequest): Flux<RulesResponse> {
        logger.info("Received ask request for conversationId: ${request.conversationId}")
        return with(aimuroChatService) { replay(ask(request)) }
    }

    @GetMapping("/ask/{requestId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable requestId: String): Flux<RulesResponse> = aimuroChatService.replay(requestId)

    @GetMapping("/conversation/{conversationId}")
    fun getConversationHistory(@PathVariable conversationId: Long): List<ChatResponse> =
        aimuroChatService.getHistory(conversationId)

    @GetMapping("/conversation/{conversationId}/status")
    fun getConversationStatus(@PathVariable conversationId: String): Map<String, String> =
        aimuroChatService.getConversationStatus(conversationId)
}

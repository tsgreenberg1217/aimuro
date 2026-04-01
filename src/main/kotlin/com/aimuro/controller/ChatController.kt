package com.aimuro.controller

import com.aimuro.history.ChatResponse
import com.aimuro.service.AimuroChatService
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
    val answer: String
)

@RestController
class ChatController(private val aimuroChatService: AimuroChatService) {

    @PostMapping("/ask")
    fun ask(@RequestBody request: ChatRequest): ResponseEntity<Map<String, String>> {
        val requestId = aimuroChatService.ask(request)
        return ResponseEntity.accepted().body(mapOf("requestId" to requestId))
    }

    @GetMapping("/ask/{requestId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable requestId: String): Flux<RulesResponse> =
        aimuroChatService.replay(requestId)

    @GetMapping("/conversation/{conversationId}")
    fun getConversationHistory(@PathVariable conversationId: Long): List<ChatResponse> =
        aimuroChatService.getHistory(conversationId)

    @GetMapping("/conversation/{conversationId}/status")
    fun getConversationStatus(@PathVariable conversationId: String): Map<String, String> =
        aimuroChatService.getConversationStatus(conversationId)
}

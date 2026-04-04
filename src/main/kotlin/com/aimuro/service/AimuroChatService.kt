package com.aimuro.service

import com.aimuro.controller.ChatRequest
import com.aimuro.history.ChatResponse
import com.aimuro.controller.RulesResponse
import reactor.core.publisher.Flux

interface AimuroChatService {
    fun ask(request: ChatRequest): String
    fun replay(requestId: String): Flux<RulesResponse>
    fun getHistory(conversationId: Long): List<ChatResponse>
    fun getConversationStatus(conversationId: String): Map<String, String>
}

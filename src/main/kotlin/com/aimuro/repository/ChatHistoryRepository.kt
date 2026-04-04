package com.aimuro.repository

import com.aimuro.history.ChatResponse
import org.springframework.data.jpa.repository.JpaRepository


interface ChatHistoryRepository : JpaRepository<ChatResponse, Long> {
    fun findByConversationId(conversationId: Long): List<ChatResponse>
}

package com.aimuro.controller

import com.aimuro.history.Conversation
import com.aimuro.repository.ConversationRepository
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ConversationController(private val conversationRepository: ConversationRepository) {

    @PostMapping("/conversation")
    fun createConversation(): Long {
        logger.info("Creating new conversation")
        return conversationRepository.save(Conversation()).id
    }

    companion object{
        private val logger = LoggerFactory.getLogger(ConversationController::class.java)
    }

}
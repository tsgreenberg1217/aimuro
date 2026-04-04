package com.aimuro.repository

import com.aimuro.history.Conversation
import org.springframework.data.jpa.repository.JpaRepository

interface ConversationRepository : JpaRepository<Conversation, Long>

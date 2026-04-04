package com.aimuro.history

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "conversation")
data class Conversation(
    @Id
    val id: Long = 0,

    val createdAt: Instant = Instant.now(),

    @OneToMany(mappedBy = "conversation", fetch = FetchType.LAZY)
    val responses: MutableList<ChatResponse> = mutableListOf()
)

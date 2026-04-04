package com.aimuro.client

import com.aimuro.model.card.CardResponse
import com.aimuro.model.card.CardFilterQuery

interface GundamCardService {

    fun findCards(filter: CardFilterQuery): List<CardResponse>

    fun findCard(name: String): List<CardResponse>
}
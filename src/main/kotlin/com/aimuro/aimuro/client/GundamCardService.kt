package com.aimuro.aimuro.client

import com.aimuro.aimuro.model.card.CardResponse
import com.aimuro.aimuro.model.card.CardFilterQuery

interface GundamCardService {

    fun findCards(filter: CardFilterQuery): List<CardResponse>

    fun findCard(name: String): List<CardResponse>
}
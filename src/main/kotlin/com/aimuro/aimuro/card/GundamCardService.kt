package com.aimuro.aimuro.card

interface GundamCardService {

    fun findCards(filter: CardFilter): List<CardDto>

    fun findCard(name: String): List<CardDto>
}

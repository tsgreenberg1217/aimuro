package com.aimuro.aimuro.card

import reactor.core.publisher.Mono

interface GundamCardService {

    fun findCards(filter: CardFilter): Mono<List<CardDto>>

    fun findCard(name: String): Mono<List<CardDto>>
}

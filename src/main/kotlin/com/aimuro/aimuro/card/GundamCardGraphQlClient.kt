package com.aimuro.aimuro.card

import org.springframework.graphql.client.HttpGraphQlClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class GundamCardGraphQlClient(
    private val graphQlClient: HttpGraphQlClient,
) : GundamCardService {

    override fun findCards(filter: CardFilter): Mono<List<CardDto>> =
        graphQlClient
            .documentName("findCards")
            .variable("filter", filter)
            .retrieve("cards")
            .toEntityList(CardDto::class.java)

    override fun findCard(name: String): Mono<List<CardDto>> =
        graphQlClient
            .documentName("findCard")
            .variable("name", name)
            .retrieve("cards")
            .toEntityList(CardDto::class.java)
}

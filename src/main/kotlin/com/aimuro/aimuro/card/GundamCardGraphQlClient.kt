package com.aimuro.aimuro.card

import org.springframework.graphql.client.HttpSyncGraphQlClient
import org.springframework.stereotype.Service

@Service
class GundamCardGraphQlClient(
    private val graphQlClient: HttpSyncGraphQlClient,
) : GundamCardService {

    override fun findCards(filter: CardFilter): List<CardDto> =
        graphQlClient
            .documentName("findCards")
            .variable("filter", filter)
            .retrieveSync("cards")
            .toEntityList(CardDto::class.java)

    override fun findCard(name: String): List<CardDto> =
        graphQlClient
            .documentName("findCard")
            .variable("name", name)
            .retrieveSync("cards")
            .toEntityList(CardDto::class.java)
}

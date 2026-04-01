package com.aimuro.client

import com.aimuro.model.card.CardResponse
import com.aimuro.model.card.CardFilterQuery
import org.springframework.graphql.client.HttpSyncGraphQlClient
import org.springframework.stereotype.Service

@Service
class GundamCardGraphQlClient(
    private val graphQlClient: HttpSyncGraphQlClient,
) : GundamCardService {

    override fun findCards(filter: CardFilterQuery): List<CardResponse> =
        graphQlClient
            .documentName("findCards")
            .variable("filter", filter)
            .retrieveSync("cards")
            .toEntityList(CardResponse::class.java)

    override fun findCard(name: String): List<CardResponse> =
        graphQlClient
            .documentName("findCard")
            .variable("name", name)
            .retrieveSync("cards")
            .toEntityList(CardResponse::class.java)
}

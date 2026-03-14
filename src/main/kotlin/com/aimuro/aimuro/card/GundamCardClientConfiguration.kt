package com.aimuro.aimuro.card

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.client.HttpSyncGraphQlClient
import org.springframework.web.client.RestClient

@Configuration
class GundamCardClientConfiguration {

    @Value("\${gundam.card.service.url}")
    private lateinit var serviceUrl: String

    @Bean
    fun gundamHttpCardGraphQlClient(): HttpSyncGraphQlClient =
        HttpSyncGraphQlClient.create(
            RestClient.builder()
                .baseUrl(serviceUrl)
                .build()
        )
}

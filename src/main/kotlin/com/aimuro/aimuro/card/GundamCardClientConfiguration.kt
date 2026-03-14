package com.aimuro.aimuro.card

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.client.HttpGraphQlClient
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class GundamCardClientConfiguration {

    @Value("\${gundam.card.service.url}")
    private lateinit var serviceUrl: String

    @Bean
    fun gundamHttpCardGraphQlClient(): HttpGraphQlClient =
        HttpGraphQlClient.create(
            WebClient.builder()
                .baseUrl(serviceUrl)
                .build()
        )
}

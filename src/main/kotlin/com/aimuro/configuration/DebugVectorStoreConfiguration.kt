package com.aimuro.configuration

import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SimpleVectorStore
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("debug")
class DebugVectorStoreConfiguration {

    @Bean
    fun vectorStore(embeddingModel: EmbeddingModel): VectorStore =
        SimpleVectorStore.builder(embeddingModel).build()
}
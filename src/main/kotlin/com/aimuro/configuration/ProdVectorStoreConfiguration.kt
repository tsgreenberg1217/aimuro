package com.aimuro.configuration

import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@Configuration
@Profile("prod")
class ProdVectorStoreConfiguration {

    @Bean("pgVectorDataSource")
    @ConfigurationProperties("app.pgvector.datasource")
    fun pgVectorDataSource(): DataSource = DataSourceBuilder.create().build()

    @Bean("pgVectorJdbcTemplate")
    fun pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") dataSource: DataSource): JdbcTemplate =
        JdbcTemplate(dataSource)

    @Bean
    fun vectorStore(
        @Qualifier("pgVectorJdbcTemplate") jdbcTemplate: JdbcTemplate,
        embeddingModel: EmbeddingModel
    ): VectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .initializeSchema(true)
        .build()
}

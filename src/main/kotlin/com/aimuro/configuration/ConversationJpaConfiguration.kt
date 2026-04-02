package com.aimuro.configuration

import com.aimuro.history.ChatResponse
import com.aimuro.history.Conversation
import com.aimuro.repository.ConversationRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource


@Configuration
@Profile("!debug")
class ConversationJpaConfiguration {

    @Bean("vectorDataSourceProperties")
    @Primary
    @ConfigurationProperties("spring.datasource")
    fun vectorDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean("dataSource")
    @Primary
    fun vectorDataSource(
        @Qualifier("vectorDataSourceProperties") vectorDataSourceProperties: DataSourceProperties,
    ): DataSource = vectorDataSourceProperties.initializeDataSourceBuilder().build()

    @Bean("conversationDataSourceProperties")
    @ConfigurationProperties("app.conversation.datasource")
    fun conversationDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean("conversationDataSource")
    fun convoDataSource(
        @Qualifier("conversationDataSourceProperties") conversationDataSourceProperties: DataSourceProperties,
    ): DataSource = conversationDataSourceProperties.initializeDataSourceBuilder().build()

    @Bean
    fun convoJdbcTemplate(@Qualifier("conversationDataSource") dataSource: DataSource) = JdbcTemplate(dataSource)
}

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
    basePackageClasses = [ConversationRepository::class],
    entityManagerFactoryRef = "conversationEntityManagerFactory",
    transactionManagerRef = "conversationTransactionManager"
)
class ConvoJpaConfiguration {
    @Bean("conversationEntityManagerFactory")
    fun conversationEntityManagerFactory(
        @Qualifier("conversationDataSource") dataSource: DataSource,
        builder: EntityManagerFactoryBuilder
    ): LocalContainerEntityManagerFactoryBean {
        return builder
            .dataSource(dataSource)
            .packages(Conversation::class.java, ChatResponse::class.java)
            .build()
    }

    @Bean
    fun conversationTransactionManager(
        @Qualifier("conversationEntityManagerFactory") conversationEntityManagerFactory: LocalContainerEntityManagerFactoryBean
    ): PlatformTransactionManager {
        return JpaTransactionManager(conversationEntityManagerFactory.getObject()!!)
    }
}
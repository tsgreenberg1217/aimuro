package com.aimuro.configuration

import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import java.util.Properties
import javax.sql.DataSource

//@Configuration
//@Profile("prod")
//@EnableJpaRepositories(
//    basePackages = ["com.aimuro.repository"],
////    entityManagerFactoryRef = "conversationEntityManagerFactory",
////    transactionManagerRef = "conversationTransactionManager"
//)
//class ConversationJpaConfiguration {
//
//    @Bean
//    @ConfigurationProperties("app.conversation.datasource")
//    fun conversationDataSourceProperties(): DataSourceProperties = DataSourceProperties()
//
////    @Bean("conversationDataSource")
////    fun conversationDataSource(conversationDataSourceProperties: DataSourceProperties): DataSource =
////        conversationDataSourceProperties.initializeDataSourceBuilder().build()
////
////
////    @Bean("conversationTransactionManager")
////    fun conversationTransactionManager(conversationEntityManagerFactory: EntityManagerFactory): JpaTransactionManager =
////        JpaTransactionManager(conversationEntityManagerFactory)
//}

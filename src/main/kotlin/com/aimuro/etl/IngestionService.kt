package com.aimuro.etl

import org.slf4j.LoggerFactory
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component

@Component("debug")
class IngestionService(
    val vectorStore: VectorStore,
    @Qualifier("markdownDocService") val docService: DocService,
    @Value("classpath:/docs/gundam_card_game_comprehensive_rules_v1_5_0.md") val comprehensiveRules: Resource,
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        // Split by ## / #### / ##### heading (H2 > H4 > H5) — each leaf section becomes
        // one document with its title prepended (see MarkdownDocService).
        val sectionDocs = docService.getDocs(comprehensiveRules)
//
        logger.info("Ingesting ${sectionDocs.size} section documents from ${comprehensiveRules.filename}")
        sectionDocs.forEach {
            logger.info("Document id ${it.id}")
            logger.info("title: ${it.metadata["title"]}")
            logger.info("metadata: ${it.metadata["keywords"]}")
            logger.info("text: ${it.text}")
            logger.info("------------------------------")
        }
        vectorStore.accept(sectionDocs)
        logger.info("Ingestion complete.")
    }

    companion object {
        val logger = LoggerFactory.getLogger(IngestionService::class.java)
    }
}
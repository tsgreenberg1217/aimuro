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
        // Split by H2 section — each section becomes one document with
        // title prepended and keyword metadata attached (see MarkdownDocService).
        // A routing index chunk is also appended automatically.
//        val sectionDocs = docService.getDocs(comprehensiveRules)
//
//        logger.info("Ingesting ${sectionDocs.size} section documents from ${comprehensiveRules.filename}")
//        vectorStore.accept(sectionDocs)
//        logger.info("Ingestion complete.")

        // Uncomment to also ingest the web rules:
        // val webDocs = docService.getDocs(webRulesRtf)
        // logger.info("Ingesting ${webDocs.size} web rule documents")
        // vectorStore.accept(webDocs)
    }

    companion object {
        val logger = LoggerFactory.getLogger(IngestionService::class.java)
    }
}
package com.aimuro.aimuro

import org.slf4j.LoggerFactory
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.util.regex.Pattern


@Component
class IngestionService(
    val pgVectorStore: PgVectorStore,
    @Qualifier("markdownDocService") val docService: DocService,
    @Value("classpath:/docs/gundam_card_game_comprehensive_rules_v1_5_0.md") val comprehensiveRules: Resource,
    @Value("classpath:/docs/gunam_rules_web.rtf") val webRulesRtf: Resource,
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        val rulesDoc = docService.getDocs(comprehensiveRules)

        val sectionDocs = rulesDoc.run(
            TokenTextSplitter.builder()
                .withChunkSize(400)
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(5)
                .withKeepSeparator(true)
                .build()
            ::apply
        )

        // 4️⃣ Ingest all chunks into the vector store
        logger.info("Ingesting ${sectionDocs.size} documents into the vector store from ${comprehensiveRules.filename}")
        pgVectorStore.accept(sectionDocs)
        logger.info("Ingested documents into the vector store!!!!!")


        val smallerSections = docService.getDocs(comprehensiveRules)

        logger.info("Ingesting ${smallerSections.size} documents into the vector store from ${comprehensiveRules.filename}")
        pgVectorStore.accept(smallerSections)
        logger.info("Ingested documents into the vector store!!!!!")


        // Ingest web rules

        val webDocs = docService.getDocs(webRulesRtf)


        logger.info("Ingesting web rules")
        pgVectorStore.accept(webDocs)
        logger.info("All done!!!")

    }

    companion object {
        val logger = LoggerFactory.getLogger(IngestionService::class.java)
    }
}
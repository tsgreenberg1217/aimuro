package com.aimuro.aimuro

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.document.id.RandomIdGenerator
import org.springframework.ai.reader.tika.TikaDocumentReader
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.util.regex.Matcher
import java.util.regex.Pattern


@Component
class IngestionService(
    val pgVectorStore: PgVectorStore,
    val docService: DocService,
    @Value("classpath:/docs/gundam_tcg_rules.rtf") val rulesPdf: Resource,
    @Value("classpath:/docs/gunam_rules_web.rtf") val webRulesRtf: Resource,
) : CommandLineRunner {

    override fun run(vararg args: String?) {
//        val sectionDocs = docService.getDocs(rulesPdf)
//
//        // 3️⃣ Token-based splitting for long sections using TokenTextSplitter
//        val rulesSplitter = TokenTextSplitter.builder()
//            .withChunkSize(600)
//            .withMinChunkSizeChars(200)
//            .withMinChunkLengthToEmbed(50)
//            .withMaxNumChunks(100)
//            .withKeepSeparator(true)
//            .build() // defaults align with best practices :contentReference[oaicite:2]{index=2}
//
//        val chunks: List<Document> = rulesSplitter.apply(sectionDocs)
//        // 4️⃣ Ingest all chunks into the vector store
//        logger.info("Ingesting ${chunks.size} documents into the vector store from ${rulesPdf.filename}")
//        pgVectorStore.accept(chunks)
//        logger.info("Ingested documents into the vector store!!!!!")



        // Ingest web rules
        val tika = TikaDocumentReader(webRulesRtf)
        val rawDocs: List<Document> = tika.read()

        logger.info("Ingesting ${rawDocs.size} raw documents from ${webRulesRtf.filename}")

        val webSplitter = TokenTextSplitter.builder()
            .withChunkSize(600)
            .withMinChunkSizeChars(200)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(100)
            .withKeepSeparator(true)
            .build()

        logger.info("Ingesting web rules")
        pgVectorStore.accept(webSplitter.apply(rawDocs))
        logger.info("All done!!!")

    }

    companion object {
        val logger = LoggerFactory.getLogger(IngestionService::class.java)
    }
}
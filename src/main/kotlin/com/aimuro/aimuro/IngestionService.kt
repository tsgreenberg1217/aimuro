package com.aimuro.aimuro

import org.slf4j.LoggerFactory
import org.springframework.ai.reader.pdf.PagePdfDocumentReader
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component

@Component
class IngestionService(
    val pgVectorStore: PgVectorStore,
    @Value("classpath:/docs/gundam_tcg_rules.pdf") val rulesPdf: Resource
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        val pdfreader = PagePdfDocumentReader(
            rulesPdf,
            PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)   // chunk by pages, not fixed chars
                .build()
        )
        val textSplitter = TokenTextSplitter(
            800,
            400,
            5,
            10000,
            true
        )
        pgVectorStore.accept(textSplitter.apply(pdfreader.get()))
        logger.info("Vector store loaded with data!!!")
    }

    companion object {
        val logger = LoggerFactory.getLogger(IngestionService::class.java)
    }
}
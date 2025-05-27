package com.aimuro.aimuro

import org.slf4j.LoggerFactory
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader
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
): CommandLineRunner {

    override fun run(vararg args: String?) {
        val pdfreader = ParagraphPdfDocumentReader(rulesPdf)
        val textSplitter = TokenTextSplitter()
        pgVectorStore.accept(textSplitter.apply(pdfreader.get()))
        logger.info("Vector store loaded with data!!!")
    }

    companion object{
        val logger = LoggerFactory.getLogger(IngestionService::class.java)
    }
}
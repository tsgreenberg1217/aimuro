package com.aimuro.etl

import org.slf4j.LoggerFactory
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import kotlin.math.roundToInt

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
        logger.info("Ingesting ${sectionDocs.size} section documents from ${comprehensiveRules.filename}")
        sectionDocs.forEach {
            logger.debug("Document id ${it.id}")
            logger.debug("title: ${it.metadata["title"]}")
            logger.debug("metadata: ${it.metadata["keywords"]}")
            logger.debug("text: ${it.text}")
            logger.debug("------------------------------")
        }

        // vectorStore.add()/accept() gives no per-document progress callback, so we batch
        // the embedding+write step ourselves and redraw a terminal progress bar after each
        // batch. Batch size is a small constant since the corpus is only ~300-350 docs.
        var processed = 0
        printProgressBar(processed, sectionDocs.size)
        sectionDocs.chunked(BATCH_SIZE).forEach { batch ->
            vectorStore.accept(batch)
            processed += batch.size
            printProgressBar(processed, sectionDocs.size)
        }
        println() // move off the \r-redrawn progress line before further log output

        logger.info("Ingestion complete.")
    }

    // \r-redrawn bar written straight to stdout, bypassing SLF4J (a logger would prefix
    // every update and put it on its own line, defeating the redraw). Needs a real TTY —
    // IDE consoles and redirected/CI output will just print each update on its own line.
    private fun printProgressBar(current: Int, total: Int, width: Int = 40) {
        if (total == 0) return
        val fraction = current.toDouble() / total
        val filled = (fraction * width).roundToInt().coerceIn(0, width)
        val bar = "=".repeat(filled) + " ".repeat(width - filled)
        val percent = (fraction * 100).roundToInt()
        print("\rIngesting [$bar] $percent% ($current/$total)")
        System.out.flush()
    }

    companion object {
        private const val BATCH_SIZE = 25
        val logger = LoggerFactory.getLogger(IngestionService::class.java)
    }
}
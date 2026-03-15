package com.aimuro.aimuro

import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service

@Service("markdownDocService")
class MarkdownDocService : DocService {

    private val splitter = TokenTextSplitter(
        /* defaultChunkSize    */ 400,
        /* minChunkSizeChars   */ 200,
        /* minChunkLengthToEmbed */ 5,
        /* maxNumChunks        */ 100,
        /* keepSeparator       */ true,
    )

    override fun getDocs(resource: Resource): List<Document> {
        val lines = resource.inputStream.bufferedReader().readLines()
        val documents = mutableListOf<Document>()

        // Three-level heading hierarchy: ## > #### > #####
        // Each level change flushes the accumulated content as its own document,
        // so e.g. "##### 13-1-7. <Suppression>" becomes its own embedded chunk
        // with the full breadcrumb "13) Keyword Effects > 13-1. Keyword Effects > 13-1-7. <Suppression>".
        var h2: String? = null
        var h4: String? = null
        var h5: String? = null
        var currentLines = mutableListOf<String>()

        fun leafTitle() = h5 ?: h4 ?: h2
        fun breadcrumb() = listOfNotNull(h2, h4, h5).joinToString(" > ")

        for (line in lines) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("## ") -> {
                    flushSection(resource, leafTitle(), breadcrumb(), h2, currentLines, documents)
                    h2 = trimmed.removePrefix("## ").trim()
                    h4 = null
                    h5 = null
                    currentLines = mutableListOf()
                }
                trimmed.startsWith("#### ") && h2 != null -> {
                    flushSection(resource, leafTitle(), breadcrumb(), h2, currentLines, documents)
                    h4 = trimmed.removePrefix("#### ").trim()
                    h5 = null
                    currentLines = mutableListOf()
                }
                trimmed.startsWith("##### ") && h2 != null -> {
                    flushSection(resource, leafTitle(), breadcrumb(), h2, currentLines, documents)
                    h5 = trimmed.removePrefix("##### ").trim()
                    currentLines = mutableListOf()
                }
                leafTitle() != null -> currentLines.add(line)
            }
        }
        flushSection(resource, leafTitle(), breadcrumb(), h2, currentLines, documents)

        // Add a routing/index chunk listing all sections — used for broad queries
        val indexText = buildIndexChunk(documents)
        documents.add(
            Document.builder()
                .text(indexText)
                .metadata("title", "Rules Index")
                .metadata("section", 0)
                .metadata("keywords", "index,overview,sections,contents,rules")
                .metadata("source", resource.filename.orEmpty())
                .build()
        )

        return documents
    }

    private fun flushSection(
        resource: Resource,
        title: String?,
        breadcrumb: String,
        h2Title: String?,
        currentLines: List<String>,
        documents: MutableList<Document>,
    ) {
        title ?: return
        val content = currentLines.joinToString("\n").trim()
        if (content.isBlank()) return

        val keywords = extractKeywords(content)
        // Section number comes from the ## header (e.g. "8) Attacking and Battles" → 8)
        val sectionNum = h2Title?.let { Regex("""^(\d+)[).]""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

        val titlePrefix = "Gundam Card Game Rules — $breadcrumb\n\n"
        val sectionDoc = Document.builder()
            .text("$titlePrefix$content")
            .metadata("title", title)
            .metadata("section", sectionNum)
            .metadata("keywords", keywords.joinToString(","))
            .metadata("source", resource.filename.orEmpty())
            .build()

        // For most fine-grained chunks (##### level) the content will be well under 400 tokens
        // and the splitter will return it as-is. The splitter only activates for genuinely long
        // sections (e.g. large ## or #### blocks without finer headings).
        val chunks = splitter.apply(listOf(sectionDoc))
        chunks.forEachIndexed { i, chunk ->
            val text = if (chunk.text.orEmpty().startsWith(titlePrefix)) chunk.text else "$titlePrefix${chunk.text}"
            documents.add(
                Document.builder()
                    .text(text)
                    .metadata(chunk.metadata)
                    .metadata("chunk_index", i)
                    .metadata("chunk_total", chunks.size)
                    .build()
            )
        }
    }

    /**
     * Builds a concise index chunk listing each section title and its key topics.
     * Helps the retriever find the right section for broad or ambiguous queries.
     */
    private fun buildIndexChunk(docs: List<Document>): String {
        val lines = mutableListOf("Gundam Card Game Rules — Section Index\n")
        for (doc in docs) {
            val title = doc.metadata["title"] as? String ?: continue
            val keywords = doc.metadata["keywords"] as? String ?: ""
            lines.add("- $title: $keywords")
        }
        return lines.joinToString("\n")
    }

    /**
     * Extracts notable game terms from section content for metadata tagging.
     * Looks for bracketed keywords like 【Burst】, angle-bracketed ones like <Blocker>,
     * and a curated list of core game terms.
     */
    private fun extractKeywords(content: String): Set<String> {
        val keywords = mutableSetOf<String>()

        // Capture 【keyword】 style terms
        Regex("""【([^】]+)】""").findAll(content).forEach {
            keywords.add(it.groupValues[1].lowercase().trim())
        }
        // Capture <keyword> style terms
        Regex("""<([^>]+)>""").findAll(content).forEach {
            keywords.add(it.groupValues[1].lowercase().trim())
        }
        // Core game terms worth always tagging if present
        val coreTerms = listOf(
            "unit", "pilot", "command", "base", "resource", "shield", "burst",
            "deploy", "attack", "battle", "damage", "destroy", "link", "ap", "hp",
            "level", "cost", "trash", "hand", "deck", "active", "rested", "blocker",
            "first strike", "support", "suppression", "high-maneuver", "token",
            "action step", "main phase", "rules management", "multiplayer"
        )
        val lower = content.lowercase()
        coreTerms.forEach { term -> if (term in lower) keywords.add(term) }

        return keywords
    }
}

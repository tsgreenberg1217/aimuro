package com.aimuro.etl.document_service

import com.aimuro.etl.DocService
import org.springframework.ai.document.Document
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service

@Service("markdownDocService")
class MarkdownDocService : DocService {

    override fun getDocs(resource: Resource): List<Document> {

        val lines = resource.inputStream.bufferedReader().readLines()
        val documents = mutableListOf<Document>()

        // Three-level heading hierarchy: ## > #### > #####
        // ###### and deeper lines are body content within their parent ##### chunk.
        // Chunking at ##### level is critical for sections like keyword effects, where
        // each ##### entry defines one concept (e.g. <Suppression>) — without it all
        // keyword definitions merge into one chunk and semantic search breaks.
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

        // Add a routing/index chunk listing all top-level sections — used for broad queries.
        // Passed through the splitter so oversized indexes are broken into bounded chunks.
//        val indexText = buildIndexChunk(documents)
//        val indexDoc = Document.builder()
//            .text(indexText)
//            .metadata("title", "Rules Index")
//            .metadata("section", 0)
//            .metadata("keywords", "index,overview,sections,contents,rules")
//            .metadata("source", resource.filename.orEmpty())
//            .build()
//        splitter.apply(listOf(indexDoc)).forEachIndexed { i, chunk ->
//            documents.add(
//                Document.builder()
//                    .text(chunk.text)
//                    .metadata(chunk.metadata)
//                    .metadata("chunk_index", i)
//                    .build()
//            )
//        }

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
        // Strip markdown heading markers and leading section numbers (e.g. "###### 13-1-7-1. ") from body lines.
        // This reduces structural noise so the keyword/concept term dominates the embedding.
        val cleanContent = currentLines
            .map { it.trimStart().replace(Regex("^#+\\s+"), "").replace(Regex("^[\\d]([\\d.-]*)\\s*\\.\\s*"), "") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (cleanContent.isBlank()) return

//        val keywords = extractKeywords(cleanContent)
        // Section number comes from the ## header (e.g. "8) Attacking and Battles" → 8)
        val sectionNum = h2Title?.let { Regex("""^(\d+)[).]""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

        // Strip the leading section number from the title (e.g. "13-1-7. <Suppression>" → "<Suppression>")
        // and use it as the only prefix — the breadcrumb is omitted because all sibling chunks share
        // the same parent path, which pulls their embeddings together and kills discrimination.
        val cleanTitle = title.replace(Regex("^[\\d]+([\\d.-]*)\\s*\\.?[).]?\\s*"), "").trim()
        val body = if (cleanTitle.isNotBlank()) "$cleanTitle\n\n$cleanContent" else cleanContent

        documents.add(
            Document.builder()
                .text("search_document: $body")
                .metadata("title", title)
                .metadata("section", sectionNum)
//                .metadata("keywords", keywords.joinToString(","))
                .metadata("source", resource.filename.orEmpty())
                .build()
        )
//        val chunks = splitter.apply(listOf(sectionDoc))
//        chunks.forEachIndexed { i, chunk ->
//            val text = if (chunk.text.orEmpty().startsWith(titlePrefix)) chunk.text else "$titlePrefix${chunk.text}"
//            documents.add(
//                Document.builder()
//                    .text(text)
//                    .metadata(chunk.metadata)
//                    .metadata("chunk_index", i)
//                    .metadata("chunk_total", chunks.size)
//                    .build()
//            )
//        }
    }

    /**
     * Builds a concise index chunk listing top-level (##) sections and their key topics.
     * Deduplicates by section number so the index stays small regardless of chunk count.
     */
    private fun buildIndexChunk(docs: List<Document>): String {
        val seen = mutableSetOf<Int>()
        val lines = mutableListOf("Gundam Card Game Rules — Section Index\n")
        for (doc in docs) {
            val section = doc.metadata["section"] as? Int ?: continue
            if (section == 0 || !seen.add(section)) continue
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

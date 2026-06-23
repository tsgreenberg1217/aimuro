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
                    flushSection(resource, leafTitle(), h2, currentLines, documents)
                    h2 = trimmed.removePrefix("## ").trim()
                    h4 = null
                    h5 = null
                    currentLines = mutableListOf()
                }

                trimmed.startsWith("#### ") && h2 != null -> {
                    flushSection(resource, leafTitle(), h2, currentLines, documents)
                    h4 = trimmed.removePrefix("#### ").trim()
                    h5 = null
                    currentLines = mutableListOf()
                }

                trimmed.startsWith("##### ") && h2 != null -> {
                    flushSection(resource, leafTitle(), h2, currentLines, documents)
                    h5 = trimmed.removePrefix("##### ").trim()
                    currentLines = mutableListOf()
                }

                leafTitle() != null -> currentLines.add(line)
            }
        }
        flushSection(resource, leafTitle(), h2, currentLines, documents)

        return documents
    }

    private fun flushSection(
        resource: Resource,
        title: String?,
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
    }
}
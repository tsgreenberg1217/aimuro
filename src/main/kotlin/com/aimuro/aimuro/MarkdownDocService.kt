package com.aimuro.aimuro

import org.springframework.ai.document.Document
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service

@Service("markdownDocService")
class MarkdownDocService : DocService {

    override fun getDocs(resource: Resource): List<Document> {
        val lines = resource.inputStream.bufferedReader().readLines()
        val documents = mutableListOf<Document>()

        var currentTitle: String? = null
        var currentSectionNum: Int? = null
        var currentLines = mutableListOf<String>()
        var sectionIndex = 0

        fun flushSection() {
            val title = currentTitle ?: return
            val content = currentLines.joinToString("\n").trim()
            if (content.isNotBlank()) {
                // Prepend the section title so embeddings carry semantic context
                val textWithHeader = "Gundam Card Game Rules — $title\n\n$content"
                val keywords = extractKeywords(content)
                documents.add(
                    Document.builder()
                        .text(textWithHeader)
                        .metadata("title", title)
                        .metadata("section", currentSectionNum ?: sectionIndex)
                        .metadata("keywords", keywords.joinToString(","))
                        .metadata("source", resource.filename.orEmpty())
                        .build()
                )
            }
        }

        for (line in lines) {
            if (line.trimStart().startsWith("## ")) {
                flushSection()
                sectionIndex++
                currentTitle = line.trimStart().removePrefix("## ").trim()
                // Extract leading section number if present, e.g. "8) Attacking and Battles" → 8
                currentSectionNum = Regex("""^(\d+)[).]""").find(currentTitle!!)?.groupValues?.get(1)?.toIntOrNull()
                currentLines = mutableListOf()
            } else if (currentTitle != null) {
                currentLines.add(line)
            }
        }
        flushSection()

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
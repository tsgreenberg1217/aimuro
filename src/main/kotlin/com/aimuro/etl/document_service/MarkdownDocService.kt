package com.aimuro.etl.document_service

import com.aimuro.etl.DocService
import org.springframework.ai.document.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service

@Service("markdownDocService")
class MarkdownDocService(
    @Value("\${app.ai.nomic-prefix:false}") private val nomicPrefix: Boolean
) : DocService {

    override fun getDocs(resource: Resource): List<Document> {

        val lines = resource.inputStream.bufferedReader().readLines()
        val documents = mutableListOf<Document>()

        // Three-level heading hierarchy: ## > #### > #####
        // ###### and deeper lines are body content within their parent ##### chunk.
        // Chunking at ##### level is critical for sections like keyword effects, where
        // each ##### entry defines one concept (e.g. <Suppression>) — without it all
        // keyword definitions merge into one chunk and semantic search breaks.
        //
        // Exception: a ###### line that itself states a qualifier/exception to a general rule
        // (matches EXCEPTION_PATTERN — "unless", "normally...but", "except", etc.) is ALSO
        // emitted as its own standalone chunk. Rules text states a general rule and its
        // exception together, but the exception is often what a targeted query is actually
        // about (e.g. "can a Link Unit attack the turn it's deployed?") — left only inside the
        // merged parent chunk, its embedding gets diluted by unrelated sibling sentences and
        // stops ranking for that query. This is scoped to the small set of lines that actually
        // read as an exception, not every ###### line, to avoid blowing up the chunk count.
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

                trimmed.startsWith("###### ") && leafTitle() != null -> {
                    currentLines.add(line)
                    val rawLine = trimmed.removePrefix("###### ").trim()
                    if (EXCEPTION_PATTERN.containsMatchIn(rawLine)) {
                        flushAtomicLine(resource, rawLine, h2, documents)
                    }
                }

                leafTitle() != null -> currentLines.add(line)
            }
        }
        flushSection(resource, leafTitle(), h2, currentLines, documents)

        return documents
    }

    // Strips markdown heading markers and leading section numbers (e.g. "###### 13-1-7-1. ") from body
    // lines. This reduces structural noise so the keyword/concept term dominates the embedding.
    private fun cleanLine(line: String): String =
        line.trimStart().replace(Regex("^#+\\s+"), "").replace(Regex("^[\\d]([\\d.-]*)\\s*\\.\\s*"), "")

    // Strips the leading section number from a title (e.g. "13-1-7. <Suppression>" → "<Suppression>").
    private fun cleanTitle(title: String): String =
        title.replace(Regex("^[\\d]+([\\d.-]*)\\s*\\.?[).]?\\s*"), "").trim()

    // Section number comes from the ## header (e.g. "8) Attacking and Battles" → 8)
    private fun sectionNumOf(h2Title: String?): Int =
        h2Title?.let { Regex("""^(\d+)[).]""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

    private fun buildDocument(resource: Resource, text: String, rawTitle: String, h2Title: String?): Document =
        Document.builder()
            .text(if (nomicPrefix) "search_document: $text" else text)
            .metadata("title", rawTitle)
            .metadata("section", sectionNumOf(h2Title))
            .metadata("source", resource.filename.orEmpty())
            .build()

    private fun flushSection(
        resource: Resource,
        title: String?,
        h2Title: String?,
        currentLines: List<String>,
        documents: MutableList<Document>,
    ) {
        title ?: return
        val cleanContent = currentLines
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()

        // The doc numbers almost every atomic rule as its own heading line, with the whole rule text
        // living in the heading itself and no separate body line following it (e.g. "##### 3-2-4.
        // Unless specified otherwise, a newly deployed Unit cannot attack on the turn it is deployed.").
        // Falling back to the title here (instead of dropping the section) is what keeps those rules —
        // dropping them silently discarded ~80% of the rulebook.
        val cleanSectionTitle = cleanTitle(title)
        if (cleanContent.isBlank() && cleanSectionTitle.isBlank()) return

        // The breadcrumb (ancestor path) is deliberately omitted as a prefix — all sibling chunks share
        // the same parent path, which pulls their embeddings together and kills discrimination.
        val body = when {
            cleanContent.isBlank() -> cleanSectionTitle
            cleanSectionTitle.isNotBlank() -> "$cleanSectionTitle\n\n$cleanContent"
            else -> cleanContent
        }

        documents.add(buildDocument(resource, body, title, h2Title))
    }

    private fun flushAtomicLine(
        resource: Resource,
        rawLine: String,
        h2Title: String?,
        documents: MutableList<Document>,
    ) {
        val clean = cleanLine(rawLine)
        if (clean.isBlank()) return
        documents.add(buildDocument(resource, clean, rawLine, h2Title))
    }

    private companion object {
        // Matches a ###### line that states a qualifier/exception to a general rule rather than
        // an unrelated standalone fact — see the getDocs() comment for why these get an extra
        // standalone chunk instead of only living inside their merged parent.
        val EXCEPTION_PATTERN = Regex("""\b(unless|normally|except|exception|however|instead)\b""", RegexOption.IGNORE_CASE)
    }
}
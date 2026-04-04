package com.aimuro.etl.document_service

import com.aimuro.etl.DocService
import com.aimuro.etl.IngestionService.Companion.logger
import org.springframework.ai.document.Document
import org.springframework.ai.document.id.RandomIdGenerator
import org.springframework.ai.reader.tika.TikaDocumentReader
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

@Service("pdfDocService")
class PdfDocService : DocService {
    // by default, the section pattern is set to match sections like "1.", "2.", etc.
    override fun getDocs(rulesPdf: Resource): List<Document> {
        val tika = TikaDocumentReader(rulesPdf)
        val rawDocs: List<Document> =
            tika.read() // extracts content with simple metadata :contentReference[oaicite:1]{index=1}


        // 2️⃣ Split each raw Document into section-based Documents
        val sectionDocs: MutableList<Document> = ArrayList()
        for (raw in rawDocs) {
            val text: String = raw.formattedContent
            val matcher: Matcher = Pattern.compile("(?m)^(\\d+\\.)").matcher(text)
            var start = 0
            var sectionId: String? = null
            while (matcher.find()) {
                if (sectionId != null) {
                    val sub = text.substring(start, matcher.start()).trim { it <= ' ' }

                    val topic = sub
                        .substringAfter(".")
                        .substringBefore("\n")

                    val doc1 = Document.builder()
                        .id(RandomIdGenerator().generateId(sub))
                        .text(sub)
                        .metadata("topic", topic)
                        .metadata("source", rulesPdf.filename.orEmpty())
                        .metadata("detail_level", "in-depth")
                        .build()

                    logger.info("CON 1: Found section: ${topic.uppercase(Locale.getDefault())} with text length: ${doc1.text?.length}")

                    sectionDocs.add(doc1)
                }
                sectionId = matcher.group(1).replace(".", "")
                start = matcher.start()
            }
            if (sectionId != null && start < text.length) {


                val sub2 = text.substring(start).trim { it <= ' ' }
                val doc2 = Document.builder()
                    .id(RandomIdGenerator().generateId(sub2))
                    .text(sub2)
                    .metadata("sectionId", sectionId)
                    .metadata("source", rulesPdf.filename.orEmpty())
                    .metadata("detail_level", "in-depth")
                    .build()

                logger.info("CON 2: Found section: $sectionId with text length: ${doc2.text?.length}")
                sectionDocs.add(doc2)
            }
        }
        return sectionDocs
    }
}
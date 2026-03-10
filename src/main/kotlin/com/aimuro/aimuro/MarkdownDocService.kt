package com.aimuro.aimuro

import org.springframework.ai.document.Document
import org.springframework.ai.reader.markdown.MarkdownDocumentReader
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.util.regex.Pattern

@Service("markdownDocService")
class MarkdownDocService : DocService{

    override fun getDocs(
        resource: Resource
    ): List<Document> {
        val config = MarkdownDocumentReaderConfig.builder()
            .withHorizontalRuleCreateDocument(true)
            .withIncludeCodeBlock(true)
            .withIncludeBlockquote(true)
            .withAdditionalMetadata("source", resource.filename.orEmpty())
            .build()

        return MarkdownDocumentReader(resource, config).read()
    }
}

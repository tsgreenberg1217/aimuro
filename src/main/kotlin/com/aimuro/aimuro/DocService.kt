package com.aimuro.aimuro

import org.springframework.ai.document.Document
import org.springframework.core.io.Resource
import java.util.regex.Pattern

interface DocService {
    fun getDocs(resource: Resource): List<Document>
}
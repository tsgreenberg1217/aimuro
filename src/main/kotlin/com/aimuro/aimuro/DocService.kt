package com.aimuro.aimuro

import org.springframework.ai.document.Document
import org.springframework.core.io.Resource

interface DocService {
    fun getDocs(resource: Resource): List<Document>
}
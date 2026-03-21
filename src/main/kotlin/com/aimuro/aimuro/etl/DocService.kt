package com.aimuro.aimuro.etl

import org.springframework.ai.document.Document
import org.springframework.core.io.Resource

interface DocService {
    fun getDocs(resource: Resource): List<Document>
}
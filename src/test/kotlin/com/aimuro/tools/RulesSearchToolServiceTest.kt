package com.aimuro.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import kotlin.test.assertEquals

class RulesSearchToolServiceTest {

    private val vectorStore = mock(VectorStore::class.java)
    private val classifier = mock(RulesComplexityClassifier::class.java)
    private val service = RulesSearchToolService(vectorStore, classifier, 0.6, false)

    @ParameterizedTest
    @CsvSource("SIMPLE,10", "MODERATE,16", "IN_DEPTH,20")
    fun `topK is derived from the classifier's depth`(depth: SearchDepth, expectedTopK: Int) {
        `when`(classifier.classify("some rules question")).thenReturn(depth)
        `when`(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest::class.java)))
            .thenReturn(emptyList())

        service.searchRules("some rules question")

        val captor = ArgumentCaptor.forClass(SearchRequest::class.java)
        verify(vectorStore).similaritySearch(captor.capture())
        assertEquals(expectedTopK, captor.value.topK)
    }

    @Test
    fun `empty results return the no-passages message`() {
        `when`(classifier.classify("q")).thenReturn(SearchDepth.SIMPLE)
        `when`(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest::class.java)))
            .thenReturn(emptyList())

        assertEquals("No relevant rules passages found for: q", service.searchRules("q"))
    }
}

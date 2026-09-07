package com.aimuro.etl.document_service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource

class MarkdownDocServiceTest {

    private val service = MarkdownDocService(nomicPrefix = false)

    private fun docsFor(markdown: String) =
        service.getDocs(ByteArrayResource(markdown.trimIndent().toByteArray()))

    @Test
    fun `title-only leaf section is kept, not dropped`() {
        val docs = docsFor(
            """
            ## 3) Card Types
            #### 3-2. Units
            ##### 3-2-4. Unless specified otherwise, a newly deployed Unit cannot attack on the turn it is deployed.
            ##### 3-2-5. Units have AP and HP.
            """
        )

        val ruleDoc = docs.single { it.metadata["title"] == "3-2-4. Unless specified otherwise, a newly deployed Unit cannot attack on the turn it is deployed." }
        assertThat(ruleDoc.text).isEqualTo("Unless specified otherwise, a newly deployed Unit cannot attack on the turn it is deployed.")
        assertThat(ruleDoc.metadata["section"]).isEqualTo(3)
    }

    @Test
    fun `bare heading with no numbered children is kept, not dropped`() {
        val docs = docsFor(
            """
            ## 1) Game Overview
            ## 2) Card Information
            #### 2-1. Card Number
            """
        )

        val overviewDoc = docs.single { it.metadata["title"] == "1) Game Overview" }
        assertThat(overviewDoc.text).isEqualTo("Game Overview")
    }

    @Test
    fun `ordinary sixth-level sub-rules stay folded into their parent chunk only`() {
        val docs = docsFor(
            """
            ## 3) Card Types
            #### 3-2. Units
            ##### 3-2-6. Only Units have link conditions.
            ###### 3-2-6-1. These are conditions that are necessary to create a Link Unit, such as 5 Pilot names or traits.
            ##### 3-2-7. Placeholder.
            """
        )

        val parent = docs.single { it.metadata["title"] == "3-2-6. Only Units have link conditions." }
        assertThat(parent.text).contains("These are conditions that are necessary to create a Link Unit, such as 5 Pilot names or traits.")

        assertThat(docs).noneMatch { it.metadata["title"] == "3-2-6-1. These are conditions that are necessary to create a Link Unit, such as 5 Pilot names or traits." }
    }

    @Test
    fun `a sixth-level sub-rule stating an exception is kept in the parent AND emitted as its own atomic chunk`() {
        val docs = docsFor(
            """
            ## 3) Card Types
            #### 3-2. Units
            ##### 3-2-6. Only Units have link conditions.
            ###### 3-2-6-1. These are conditions that are necessary to create a Link Unit, such as 5 Pilot names or traits.
            ###### 3-2-6-3. Units normally cannot attack during the turn in which they are deployed. Link Units can immediately attack during the turn in which they are deployed.
            ##### 3-2-7. Placeholder.
            """
        )

        val parent = docs.single { it.metadata["title"] == "3-2-6. Only Units have link conditions." }
        assertThat(parent.text).contains("Only Units have link conditions.")
        assertThat(parent.text).contains("Link Units can immediately attack during the turn in which they are deployed.")

        val atomic = docs.single {
            it.metadata["title"] == "3-2-6-3. Units normally cannot attack during the turn in which they are deployed. Link Units can immediately attack during the turn in which they are deployed."
        }
        assertThat(atomic.text)
            .isEqualTo("Units normally cannot attack during the turn in which they are deployed. Link Units can immediately attack during the turn in which they are deployed.")
        assertThat(atomic.metadata["section"]).isEqualTo(3)
    }

    @Test
    fun `nomic prefix is applied when enabled`() {
        val prefixedService = MarkdownDocService(nomicPrefix = true)
        val docs = prefixedService.getDocs(
            ByteArrayResource(
                """
                ## 3) Card Types
                #### 3-2. Units
                ##### 3-2-4. A short rule.
                """.trimIndent().toByteArray()
            )
        )

        assertThat(docs).isNotEmpty
        assertThat(docs).allMatch { it.text?.startsWith("search_document: ") == true }
    }
}

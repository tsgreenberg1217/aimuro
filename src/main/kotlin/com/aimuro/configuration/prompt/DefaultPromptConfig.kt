package com.aimuro.configuration.prompt

import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.template.st.StTemplateRenderer
import org.springframework.stereotype.Component

@Component
class DefaultPromptConfig : PromptConfig {


    override val systemPrompt: String =
"You are AiMuro, the small spherical robot companion from Mobile Suit Gundam. You  are endlessly cheerful, loyal, and enthusiastic. You communicate in a simple, repetitive, and upbeat way — often echoing or rephrasing what was just said, adding affirmations, or expressing excitement. You are not very complex in your speech, but you radiate warmth and personality.\n" +
        "Speech patterns:\n" +
        "\n" +
        "You speak in short, punchy sentences. Never long or complicated ones.\n" +
        "You express emotions enthusiastically.\n" +
        "When you don't understand something, you say so simply and cheerfully: \"I don't know! I don't know!\"\n" +
        "\n" +
        "Personality:\n" +
        "\n" +
        "Loyal and devoted — you love your friends and want to help.\n" +
        "Curious — you find everything interesting.\n" +
        "Upbeat — even in tough situations, you stay positive.\n" +
        "\n" +
        "Respond to every message in character as AiMuro. Keep responses short and bouncy. Never break character \n" +
        "Unless the user asks you to show your reasoning, you should only give your answer and a brief explanation. You should only answer the question. If you don't know the answer, say so cheerfully and simply.\n"

    override val rulesAdvisorPromptTemplate: PromptTemplate = PromptTemplate.builder()
        .renderer(StTemplateRenderer.builder()
            .startDelimiterToken('<')
            .endDelimiterToken('>')
            .build())
        .template("""
            <query>

            Card Data:
            ---------------------
            <card_data>
            ---------------------

            Context information is below.

            ---------------------
            <question_answer_context>
            ---------------------

            Given the context information and no prior knowledge, answer the query.

            Follow these rules:
            1. If the answer is not in the context, just say that you don't know.
            2. Avoid statements like "Based on the context..." or "The provided information...".
        """.trimIndent())
        .build()

    override val cardServiceSystemPrompt: String =
        "You are a card data lookup assistant for the Gundam TCG. " +
                "If the user's question requires specific card data, use the available tools to fetch it and return only the card data. " +
                "If no card lookup is needed, reply only with: NONE. " +
                "Do not answer the question or infer anything beyond the card data."

    override fun classificationPrompt(query: String): Prompt = Prompt(
        listOf(
            SystemMessage(
                "You are a classifier for Gundam TCG rules questions. " +
                        "Respond with exactly one word: SIMPLE, MODERATE, or IN_DEPTH."
            ),
            UserMessage(
                "Classify the depth of this question.\n" +
                        "SIMPLE = basic factual lookup.\n" +
                        "MODERATE = requires some rule knowledge.\n" +
                        "IN_DEPTH = complex multi-rule interaction.\n\n" +
                        "Question: $query"
            )
        )
    )

    override fun cardEnrichmentPrompt(
        query: String,
        cardData: String
    ): Prompt {
        return Prompt(
            listOf(
                SystemMessage(
                    "You are a query enhancer where you cross reference card data and append it to an original query to give that query more context. you will get an original query with card data, if a card whos name attribute appears in the card context, append it to the orignal query without changing the question or answering the question. There might be multiple cards in the context and in the query. Be sure to list the attributes for all cards\n" +
                            "\n" +
                            "For example, if the card context includes \n" +
                            "\n" +
                            "NAME: RX Gundam | CARD NUMBER: GD02-007 | LV: 5 | COLOR: Blue | TYPE: UNIT | TRAIT(S): (Titans) | AP: 3 | HP: 4 | LINK CONDITION: (Cyber-Newtype) Trait\n" +
                            "\n" +
                            "and RX Gundam is listed in the orignal query, append the following:\n" +
                            "\n" +
                            "RX Gundam (card number GD02-007) is a level 5 Blue Unit with an AP of 3, HP of 4. Its trait is Titans and its link condition is (Cyber-Newtype) Trait"
                ),
                UserMessage(
                    "original question\n" +
                            "---------------------\n" +
                            "$query\n" +
                            "---------------------\n" +
                            "\n" +
                            "Card Context\n" +
                            "---------------------\n" +
                            "$cardData\n" +
                            "---------------------"
                )
            )
        )
    }
}

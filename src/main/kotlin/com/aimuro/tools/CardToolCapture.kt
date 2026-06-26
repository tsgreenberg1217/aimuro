package com.aimuro.tools

import com.aimuro.model.card.CardFilterQuery
import org.springframework.ai.tool.annotation.Tool

class CardToolCapture(private val delegate: CardToolService) {

    val results = mutableListOf<String>()

    @Tool(description = "Look up a Gundam TCG card by name. ALWAYS call this when the user mentions or asks about a specific card name — including questions like 'what is X', 'tell me about X', 'what does X do', or any ruling involving a named card.")
    fun findCard(name: String): String {
        val result = delegate.findCard(name)
        if (!result.startsWith("An error occurred") && !result.startsWith("No card found")) {
            results.add(result)
        }
        return result
    }

    @Tool(description = "Find Gundam TCG cards matching filter criteria. Use when the user asks about multiple cards or a category of cards. Supports filtering by color (e.g. RED, BLUE, GREEN, WHITE, PURPLE), unit (mobile suit trait), level (e.g. 1, 2, 3), and/or cost (e.g. 1, 2, 3).")
    fun findCards(filter: CardFilterQuery): String {
        val result = delegate.findCards(filter)
        if (!result.startsWith("No cards found")) {
            results.add(result)
        }
        return result
    }
}

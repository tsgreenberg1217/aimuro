package com.aimuro.aimuro.tools

import com.aimuro.aimuro.client.GundamCardService
import com.aimuro.aimuro.model.card.CardFilterQuery
import com.aimuro.aimuro.model.card.toCondensedString
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Service

@Service
class CardToolService(
    private val cardService: GundamCardService
) {

    private val logger = LoggerFactory.getLogger(CardToolService::class.java)

    @Tool(description = "Look up a Gundam TCG card by name. ALWAYS call this when the user mentions or asks about a specific card name — including questions like 'what is X', 'tell me about X', 'what does X do', or any ruling involving a named card.")
    fun findCard(name: String): String {
        try{
            logger.info("findCard called with name='{}'", name)
            val cards = cardService.findCard(name)
            val response =  if (cards.isEmpty()) "No card found with name: $name"
            else cards.toCondensedString()
            logger.info("findCard response: {} ", response)
            return response
        }catch (e: Exception){
            logger.error("Error in findCard for name='{}'", name, e)
            return "An error occurred while looking up the card: ${e.message}"
        }

    }

    @Tool(description = "Find Gundam TCG cards matching filter criteria. Use when the user asks about multiple cards or a category of cards. Supports filtering by color (e.g. RED, BLUE, GREEN, WHITE, PURPLE), unit (mobile suit trait), level (e.g. 1, 2, 3), and/or cost (e.g. 1, 2, 3).")
    fun findCards(filter: CardFilterQuery): String {
        logger.info("findCards called with filter={}", filter)
        val cards = cardService.findCards(filter)
        logger.info("findCards returned {} result(s) for filter={}", cards.size, filter)
        return if (cards.isEmpty()) "No cards found matching the given filters."
        else cards.toCondensedString()
    }
}

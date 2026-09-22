package com.aimuro.configuration

import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RoundTripLoggingAdvisorTest {

    private fun response(message: AssistantMessage, finishReason: String?, usage: DefaultUsage? = null): ChatResponse {
        val generationMetadata = ChatGenerationMetadata.builder().apply { finishReason?.let { finishReason(it) } }.build()
        val metadata = ChatResponseMetadata.builder().apply { usage?.let { usage(it) } }.build()
        return ChatResponse.builder()
            .generations(listOf(Generation(message, generationMetadata)))
            .metadata(metadata)
            .build()
    }

    @Test
    fun `tool-call round shows the tool call, its finish reason and usage`() {
        val toolCallMessage = AssistantMessage.builder()
            .content("")
            .toolCalls(listOf(AssistantMessage.ToolCall("call-1", "function", "searchRules", "{\"query\":\"Link Units\"}")))
            .build()

        val rendered = renderRound(
            index = 1,
            newMessages = listOf(UserMessage("Can a Link Unit attack?")),
            response = response(toolCallMessage, "tool_calls", DefaultUsage(120, 30, 150)),
        )

        assertContains(rendered, "Round-trip 1 (main-chat)")
        assertContains(rendered, "[USER] Can a Link Unit attack?")
        assertContains(rendered, "toolCall name=searchRules arguments={\"query\":\"Link Units\"}")
        assertContains(rendered, "finishReason=tool_calls")
        assertContains(rendered, "usage: promptTokens=120 completionTokens=30 totalTokens=150")
    }

    @Test
    fun `final round shows the answer and stop reason`() {
        val rendered = renderRoundResponse(response(AssistantMessage("Yes, on the turn it is deployed."), "stop"))

        assertContains(rendered, "[ASSISTANT] Yes, on the turn it is deployed.")
        assertContains(rendered, "finishReason=stop")
        assertFalse(rendered.contains("toolCall"))
    }

    @Test
    fun `missing finish reason and usage render as n-a instead of failing`() {
        val rendered = renderRoundResponse(response(AssistantMessage("partial"), finishReason = null))

        assertContains(rendered, "finishReason=n/a")
        assertContains(rendered, "usage: promptTokens=n/a completionTokens=n/a totalTokens=n/a")
    }

    @Test
    fun `null response renders a placeholder`() {
        assertEquals("<no response>", renderRoundResponse(null))
    }
}

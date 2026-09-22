package com.aimuro.configuration

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClientMessageAggregator
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux

// Logs every model round-trip of aimuroChatClient's tool-calling loop with its own output, stop
// reason (finishReason) and token usage.
//
// Since Spring AI 2.0 the loop is ToolCallingAdvisor *inside* the advisor chain, and each
// iteration re-enters the advisors ordered after it. So this advisor, ordered just after
// ToolCallingAdvisor (order must be strictly greater than its DEFAULT_ORDER, otherwise it would
// only wrap the whole loop once), sees one request/response pair per round-trip — no need to
// infer a round's output from the next round's prompt like the Micrometer handler had to.
//
// Registered only on aimuroChatClient (ChatBotConfiguration); it is the "main-chat" transcript
// writer. Tool-less requests have no loop, so it simply logs the single round.
@Component
class RoundTripLoggingAdvisor(
    private val synthesizedPromptFileWriter: SynthesizedPromptFileWriter,
) : StreamAdvisor {

    private val logger = LoggerFactory.getLogger(RoundTripLoggingAdvisor::class.java)

    override fun getName(): String = "RoundTripLoggingAdvisor"

    override fun getOrder(): Int = ToolCallingAdvisor.DEFAULT_ORDER + 100

    // Flux.defer so the round index is claimed per subscription, in loop order, rather than at
    // assembly time.
    override fun adviseStream(request: ChatClientRequest, chain: StreamAdvisorChain): Flux<ChatClientResponse> =
        Flux.defer {
            val instructions = request.prompt().instructions
            val (index, previouslySeenCount) = synthesizedPromptFileWriter.beginRoundTrip(instructions.size)
            val newMessages = instructions.drop(previouslySeenCount)

            ChatClientMessageAggregator().aggregateChatClientResponse(chain.nextStream(request)) { aggregated ->
                val response = aggregated.chatResponse()
                // The prompt itself is already logged by ChatModelIoLoggingHandler; this line adds
                // the part it can't get right for streaming rounds — this round's own response.
                logger.info("LLM round-trip response (main-chat)\n{}", renderRoundResponse(response))
                synthesizedPromptFileWriter.appendRoundTrip(renderRound(index, newMessages, response))
            }
        }
}

// Transcript block for one round: the messages new since the previous round, then this round's
// response. Kept pure (no Reactor, no I/O) so it can be unit-tested directly. `label` identifies
// which call site this round-trip belongs to (defaults to "main-chat" for this file's own use;
// RulesAgentRoundTripLoggingAdvisor passes "rules-agent" so its own logs/rules/*.txt transcripts
// aren't mislabeled).
internal fun renderRound(index: Int, newMessages: List<Message>, response: ChatResponse?, label: String = "main-chat"): String = buildString {
    appendLine()
    appendLine("--- Round-trip $index ($label) ---")
    appendLine(newMessages.joinToString("\n") { renderMessage(it) })
    appendLine("--- Response (round-trip $index) ---")
    appendLine(renderRoundResponse(response))
}

internal fun renderRoundResponse(response: ChatResponse?): String {
    val generation = response?.result ?: return "<no response>"
    // Spring AI substitutes an empty Usage (all zeros, not null) when the provider reports none —
    // e.g. streaming without stream usage enabled — and a real call never has 0 total tokens, so
    // treat that as "not reported" rather than printing a misleading 0.
    val usage = response.metadata.usage
    val usageReported = (usage.totalTokens ?: 0) > 0
    return buildString {
        appendLine(renderMessage(generation.output))
        appendLine("finishReason=${generation.metadata.finishReason ?: "n/a"}")
        append(
            if (usageReported) {
                "usage: promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens} totalTokens=${usage.totalTokens}"
            } else {
                "usage: promptTokens=n/a completionTokens=n/a totalTokens=n/a"
            },
        )
    }
}

internal fun renderMessage(message: Message): String = when (message) {
    is AssistantMessage -> {
        val toolCalls = message.toolCalls.joinToString("\n") { call ->
            "  toolCall name=${call.name} arguments=${call.arguments}"
        }
        buildString {
            append("[${message.messageType}] ${message.text.orEmpty()}")
            if (toolCalls.isNotBlank()) append("\n$toolCalls")
        }
    }
    is ToolResponseMessage -> message.responses.joinToString("\n") { response ->
        "[${message.messageType}] tool=${response.name} response=${response.responseData}"
    }
    else -> "[${message.messageType}] ${message.text}"
}

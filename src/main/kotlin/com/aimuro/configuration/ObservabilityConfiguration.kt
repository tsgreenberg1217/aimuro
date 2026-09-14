package com.aimuro.configuration

import com.aimuro.configuration.prompt.PromptConfig
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ObservabilityConfiguration(
    private val promptConfig: PromptConfig,
    private val synthesizedPromptFileWriter: SynthesizedPromptFileWriter,
) {

    // Declaring the app's only ObservationRegistry bean matters beyond wiring this one handler:
    // OpenAiChatAutoConfiguration/OllamaChatAutoConfiguration both resolve their registry via
    // ObjectProvider<ObservationRegistry>.getIfUnique(() -> ObservationRegistry.NOOP), so with no
    // registry bean present at all (the state before this file existed), every chat model in this
    // app was silently running with ObservationRegistry.NOOP — no per-round-trip hook was active
    // for any provider/profile combination.
    @Bean
    fun observationRegistry(): ObservationRegistry =
        ObservationRegistry.create().apply {
            observationConfig().observationHandler(ChatModelIoLoggingHandler(promptConfig, synthesizedPromptFileWriter))
        }
}

// ChatModelObservationContext fires once per actual model call — i.e. once per round-trip of
// Spring AI's internal tool-calling loop, which happens inside the ChatModel implementation
// below the ChatClient advisor chain and so is otherwise invisible (a ChatClient-level advisor
// like SimpleLoggerAdvisor only ever sees the first outgoing request and the final aggregated
// response). This handler logs every round-trip: the full outgoing prompt (including any prior
// tool-call/tool-result messages accumulated by earlier rounds) and the full outgoing response
// (a tool-call request, or the final answer text).
class ChatModelIoLoggingHandler(
    private val promptConfig: PromptConfig,
    private val synthesizedPromptFileWriter: SynthesizedPromptFileWriter,
) : ObservationHandler<ChatModelObservationContext> {

    private val logger = LoggerFactory.getLogger(ChatModelIoLoggingHandler::class.java)

    override fun supportsContext(context: Observation.Context): Boolean =
        context is ChatModelObservationContext

    override fun onStop(context: ChatModelObservationContext) {
        val roundTripId = System.identityHashCode(context)
        val callSite = callSiteOf(context)
        val promptText = context.request.instructions.joinToString("\n") { renderMessage(it) }
        val responseText = renderResponse(context)

        logger.info(
            "LLM round-trip [{}] callSite={}\n--- prompt ---\n{}\n--- response ---\n{}",
            roundTripId, callSite, promptText, responseText,
        )

        if (callSite == "main-chat") appendToSynthesizedPromptFile(context)
    }

    // Renders only the messages new since the previous round-trip of this request — Spring AI
    // resends the full accumulated instruction list on every tool-calling loop iteration, so
    // rendering the whole thing each time would duplicate the system prompt/history repeatedly.
    //
    // Deliberately does NOT print this round's own response via renderResponse(context): under a
    // streaming internal-tool-calling loop, context.response for every round-trip but the true last
    // one is aliased to the FINAL aggregated ChatResponse rather than that round's actual output
    // (confirmed by reproduction — distinct context identityHashCodes per round, yet identical
    // response text on all of them, including round 1 before any tool had run). Each round's real
    // output is already visible for free as the leading part of the NEXT round's prompt delta
    // above (the assistant tool-call + tool-result messages Spring AI appends before firing the
    // next round). The one genuinely-final answer is appended once, from ground truth, by
    // AgenticChatOrchestrator via SynthesizedPromptFileWriter.finishRequest().
    private fun appendToSynthesizedPromptFile(context: ChatModelObservationContext) {
        val instructions = context.request.instructions
        val (index, previouslySeenCount) = synthesizedPromptFileWriter.beginRoundTrip(instructions.size)
        val newMessages = instructions.drop(previouslySeenCount).joinToString("\n") { renderMessage(it) }

        val block = buildString {
            appendLine()
            appendLine("--- Round-trip $index (main-chat) ---")
            appendLine(newMessages)
        }
        synthesizedPromptFileWriter.appendRoundTrip(block)
    }

    private fun callSiteOf(context: ChatModelObservationContext): String {
        val systemText = context.request.instructions
            .firstOrNull { it.messageType == MessageType.SYSTEM }
            ?.text
        return when (systemText) {
            promptConfig.plannerSystemPrompt -> "planner"
            promptConfig.systemPrompt -> "main-chat"
            promptConfig.characterSystemPrompt -> "character"
            else -> "unknown"
        }
    }

    private fun renderMessage(message: Message): String = when (message) {
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
        is SystemMessage -> "[${message.messageType}] ${message.text}"
        else -> "[${message.messageType}] ${message.text}"
    }

    private fun renderResponse(context: ChatModelObservationContext): String {
        val output = context.response?.result?.output ?: return "<no response>"
        if (output.hasToolCalls()) {
            return output.toolCalls.joinToString("\n") { call ->
                "toolCall name=${call.name} arguments=${call.arguments}"
            }
        }
        return output.text.orEmpty()
    }
}

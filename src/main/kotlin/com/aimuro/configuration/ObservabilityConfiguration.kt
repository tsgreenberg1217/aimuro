package com.aimuro.configuration

import com.aimuro.configuration.prompt.PromptConfig
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ObservabilityConfiguration(
    private val promptConfig: PromptConfig,
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
            observationConfig().observationHandler(ChatModelIoLoggingHandler(promptConfig))
        }
}

// ChatModelObservationContext fires once per actual model call, for every ChatModel in the app
// (main chat, planner, complexity classifier). This handler logs the full outgoing prompt of each.
//
// For the "main-chat" call site it deliberately does NOT log the response: under a streaming
// tool-calling loop, context.response for every round-trip but the last is aliased to the FINAL
// aggregated ChatResponse rather than that round's actual output (confirmed by reproduction —
// distinct context identityHashCodes per round, yet identical response text on all of them). The
// correct per-round output, finishReason and usage come from RoundTripLoggingAdvisor instead,
// which also writes the per-request transcript file. The planner/classifier are single one-shot
// calls, so their response here is reliable.
class ChatModelIoLoggingHandler(
    private val promptConfig: PromptConfig,
) : ObservationHandler<ChatModelObservationContext> {

    private val logger = LoggerFactory.getLogger(ChatModelIoLoggingHandler::class.java)

    override fun supportsContext(context: Observation.Context): Boolean =
        context is ChatModelObservationContext

    override fun onStop(context: ChatModelObservationContext) {
        val roundTripId = System.identityHashCode(context)
        val callSite = callSiteOf(context)
        val promptText = context.request.instructions.joinToString("\n") { renderMessage(it) }

        if (callSite == "main-chat") {
            logger.info("LLM round-trip [{}] callSite={}\n--- prompt ---\n{}", roundTripId, callSite, promptText)
        } else {
            logger.info(
                "LLM round-trip [{}] callSite={}\n--- prompt ---\n{}\n--- response ---\n{}",
                roundTripId, callSite, promptText, renderResponse(context),
            )
        }
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

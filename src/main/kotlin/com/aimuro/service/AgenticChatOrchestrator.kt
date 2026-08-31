package com.aimuro.service

import com.aimuro.planner.QueryPlannerService
import com.aimuro.tools.CardToolService
import com.aimuro.tools.RulesSearchToolService
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux

// Shared by AimuroChatServiceImpl (prod, Redis-backed streaming) and DebugAimuroChatServiceImpl
// (in-memory streaming) — both need the identical plan-then-call sequence below.
@Component
class AgenticChatOrchestrator(
    private val chatClient: ChatClient, // unqualified injection resolves to the @Primary aimuroChatClient bean
    private val queryPlannerService: QueryPlannerService,
    private val cardToolService: CardToolService,
    private val rulesSearchToolService: RulesSearchToolService,
) {

    private val logger = LoggerFactory.getLogger(AgenticChatOrchestrator::class.java)

    fun streamResponse(history: List<Message>, userQuery: String): Flux<String> {
        val plan = queryPlannerService.plan(userQuery)

        val tools = buildList {
            if (plan.needsCardLookup) add(cardToolService)
            if (plan.needsRulesLookup) add(rulesSearchToolService)
        }
        logger.info("AgenticChatOrchestrator: tools offered for this request: {}", tools.map { it::class.simpleName })

        return chatClient
            .prompt()
            .messages(history)
            .user(userQuery)
            .apply {
                // .tools(...) is attached only per-request, never as a client-wide default (see
                // ChatBotConfiguration — aimuroChatClient has no .defaultTools()) so that the
                // no-tools-needed path above is unambiguous: an empty list here means the model
                // genuinely never sees the tools and cannot call them.
                //
                // When tools ARE attached, everything from here on — deciding whether/what/how many
                // times to call them, executing them, feeding results back, and looping until the
                // model produces a final answer — is handled internally by Spring AI's tool-calling
                // loop (ToolCallingManager, active by default via internalToolExecutionEnabled). This
                // one .stream() call may involve multiple model round-trips under the hood; we only
                // ever see the final streamed answer.
                if (tools.isEmpty()) this else tools(*tools.toTypedArray())
            }
            .stream()
            .content()


    }
}

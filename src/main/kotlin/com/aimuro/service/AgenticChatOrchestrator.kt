package com.aimuro.service

import com.aimuro.configuration.CharacterChatClient
import com.aimuro.configuration.SynthesizedPromptFileWriter
import com.aimuro.planner.QueryPlan
import com.aimuro.planner.QueryPlannerService
import com.aimuro.planner.ToolTarget
import com.aimuro.tools.CardToolService
import com.aimuro.tools.RulesAgentService
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
    @CharacterChatClient private val characterChatClient: ChatClient,
    private val queryPlannerService: QueryPlannerService,
    private val cardToolService: CardToolService,
    private val rulesAgentService: RulesAgentService,
    private val synthesizedPromptFileWriter: SynthesizedPromptFileWriter,
) {

    private val logger = LoggerFactory.getLogger(AgenticChatOrchestrator::class.java)

    fun streamResponse(history: List<Message>, userQuery: String): Flux<String> {
        synthesizedPromptFileWriter.startNewRequest(userQuery)

        val plan = queryPlannerService.plan(userQuery)

        // rulesAgentService.answerRulesQuestion is a real @Tool here, exactly like cardToolService's
        // findCard/findCards — the orchestrator only decides whether rules research is offered at
        // all (gated on needsRulesLookup), never whether/how many times it's actually invoked. That
        // decision belongs to aimuroChatClient's own tool-calling loop, same as it already is for
        // card lookups.
        val tools = buildList {
            if (plan.needsCardLookup) add(cardToolService)
            if (plan.needsRulesLookup) add(rulesAgentService)
        }
        logger.info("AgenticChatOrchestrator: tools offered for this request: {}", tools.map { it::class.simpleName })

        val toolHints = buildToolHints(plan)
        val userMessage = if (toolHints.isBlank()) userQuery
            else "<question>\n$userQuery\n</question>\n\n<tool_routing>\n$toolHints\n</tool_routing>"

        val answerChunks = mutableListOf<String>()

        // Stage 1: reason out a correct, neutral answer. This runs synchronously (.call(), not
        // .stream()) because stage 2 needs the complete answer before it can rewrite it — nothing
        // reaches the client until this finishes.
        return chatClient
            .prompt()
            .messages(history)
            .user(userMessage)
            .apply {
                // Tools are attached only per-request, never as a client-wide default (see
                // ChatBotConfiguration — aimuroChatClient has no .defaultTools()) so that the
                // no-tools-needed path above is unambiguous: an empty list here means the model
                // genuinely never sees the tools and cannot call them.
                //
                // When tools ARE attached, everything from here on — deciding whether/what/how many
                // times to call them, executing them, feeding results back, and looping until the
                // model produces a final answer — is handled by Spring AI 2.0's ToolCallingAdvisor
                // in the advisor chain. This one .stream() may involve multiple model round-trips;
                // RoundTripLoggingAdvisor logs each one. A round that calls rulesAgentService.answerRulesQuestion
                // runs the rules agent's own separate, internal tool-calling loop (its own
                // round-trips against rulesAgentChatClient) before returning a single finding as
                // this round's tool result.
                if (tools.isEmpty()) this else tools(*tools.toTypedArray())
            }
            .stream()
            .content()
            .doOnNext { answerChunks.add(it) }
            // The literal text streamed to the client, appended to the synthesized-prompt transcript
            // as a cross-check against the final round-trip RoundTripLoggingAdvisor wrote.
            .doOnComplete {
                // Best-effort ordering only (this file is a debug-only diagnostic transcript, not a
                // correctness-critical artifact): the per-round-trip writes in
                // RoundTripLoggingAdvisor may land on a different thread than this completion
                // signal, so give them a generous head start to land first rather than introducing
                // real cross-thread synchronization. Runs on its own virtual thread — never block
                // this shared Flux's completion signal, since
                // AimuroChatServiceImpl/DebugAimuroChatServiceImpl also hang their own
                // doOnComplete (history save, SSE done sentinel) off of it.
                val fullAnswer = answerChunks.joinToString("")
                Thread.startVirtualThread {
                    Thread.sleep(300)
                    synthesizedPromptFileWriter.finishRequest(fullAnswer)
                }
            }

        // Stage 2: rewrite the finished, correct answer in AiMuro's voice. This is the only leg
        // that actually streams to the client — a pure text transform, no tools, no history.
//        return characterChatClient
//            .prompt()
//            .user(buildCharacterPrompt(userQuery, intelligentAnswer))
//            .stream()
//            .content()
    }

    private fun buildCharacterPrompt(userQuery: String, intelligentAnswer: String): String = """
        <question>
        $userQuery
        </question>

        <answer>
        $intelligentAnswer
        </answer>

        Restate the answer above in AiMuro's voice (do not add, remove, or change any information — only change how it's expressed).
    """.trimIndent()

    // Lists the planner's sub-questions per tool in the user message, so the model has a focused
    // query for each tool it's given instead of deriving one from a compound raw query. How to act
    // on this block is a standing instruction — see "Tool Routing Rule" in prompts/system-prompt.md.
    // "Rules research" questions are routed to rulesAgentService.answerRulesQuestion, not a raw
    // rules search — the model decides whether/how many times to call it, same as card lookups.
    private fun buildToolHints(plan: QueryPlan): String =
        listOf("Card lookups" to ToolTarget.CARD_LOOKUP, "Rules research" to ToolTarget.RULES_LOOKUP)
            .mapNotNull { (label, tool) ->
                val questions = plan.subQuestions.filter { it.tool == tool }.map { it.question }
                if (questions.isEmpty()) null else "$label:\n" + questions.joinToString("\n") { "- \"$it\"" }
            }
            .joinToString("\n\n")
}

package com.aimuro.configuration

import com.aimuro.configuration.prompt.PromptConfig
import com.aimuro.tools.RulesSearchToolService
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Qualifier
annotation class PlannerChatClient

@Qualifier
annotation class CharacterChatClient

@Qualifier
annotation class ComplexityChatClient

@Qualifier
annotation class RulesAgentChatClient

@Configuration
class ChatBotConfiguration {

    // Dedicated low-overhead client for QueryPlannerService's structured-output planning call —
    // deliberately separate from the primary client so the planner never has tools attached to
    // it and can't itself get pulled into a tool-calling loop. Rides the same profile-switched
    // chatClientBuilder as aimuroChatClient — ollama under the ollama profile, OpenAI under
    // openai — so planning cost follows whichever provider is active, rather than forcing a
    // separate always-on Ollama dependency.
    @Bean
    @PlannerChatClient
    fun plannerChatClient(
        chatClientBuilder: ChatClient.Builder,
        promptConfig: PromptConfig,
    ): ChatClient = chatClientBuilder
        .defaultSystem(promptConfig.plannerSystemPrompt)
        .build()

    // Client for RulesComplexityClassifier's one-shot structured-output call. Rides the same
    // profile-switched chatClientBuilder as aimuroChatClient/plannerChatClient — ollama under
    // the ollama profile, OpenAI under openai. No tools attached.
    @Bean
    @ComplexityChatClient
    fun complexityChatClient(
        chatClientBuilder: ChatClient.Builder,
        promptConfig: PromptConfig,
    ): ChatClient = chatClientBuilder
        .defaultSystem(promptConfig.rulesComplexitySystemPrompt)
        .build()

    @Bean
    @Primary
    fun aimuroChatClient(
        chatClientBuilder: ChatClient.Builder,
        promptConfig: PromptConfig,
        roundTripLoggingAdvisor: RoundTripLoggingAdvisor,
    ): ChatClient {
        // No .defaultTools(...) here on purpose: tools are attached per-request in
        // AgenticChatOrchestrator based on the planner's output, so a request that needs
        // neither lookup genuinely has no tools available rather than relying on
        // request-level .tools() to "override" a client-wide default.
        //
        // Provider is profile-controlled (spring.ai.model.chat, application-{ollama,openai}.yaml)
        // — activate `openai` for o4-mini, `ollama` for qwen2.5:7b-instruct. o4-mini is the
        // stronger choice for this bean specifically: qwen2.5-instruct was observed missing
        // implicit second tool-calls (e.g. not re-querying searchRules for a term like "Link
        // Units" not already covered) that o4-mini catches reliably.
        //
        // roundTripLoggingAdvisor is ordered inside Spring AI 2.0's ToolCallingAdvisor loop, so it
        // logs each round-trip's output/finishReason/usage. ChatClient.Builder is prototype-scoped,
        // so this doesn't leak onto characterChatClient.
        return chatClientBuilder
            .defaultSystem(promptConfig.systemPrompt)
            .defaultAdvisors(roundTripLoggingAdvisor)
            .build()
    }

    // Client for the rules-agent sub-call: given one focused rules sub-question, it owns the entire
    // searchRules tool-calling loop (including any implicit follow-up search, e.g. re-querying
    // "Link Unit" once that term surfaces unexplained in a retrieved passage) and returns one
    // synthesized, evidence-backed finding. Invoked directly by RulesAgentService — never attached
    // to aimuroChatClient's own tool list; see AgenticChatOrchestrator.
    //
    // Rides the same profile-switched chatClientBuilder as aimuroChatClient/plannerChatClient/
    // complexityChatClient (o4-mini under openai, qwen2.5:7b-instruct under ollama).
    //
    // searchRules is hardcoded via .defaultTools(...) rather than attached per-request: this
    // client's tool set never varies (always exactly RulesSearchToolService), unlike aimuroChatClient
    // which conditionally attaches 0/1/2 tools per request. No circular dependency: RulesSearchToolService
    // depends only on VectorStore/RulesComplexityClassifier, nothing back on this configuration class.
    @Bean
    @RulesAgentChatClient
    fun rulesAgentChatClient(
        chatClientBuilder: ChatClient.Builder,
        promptConfig: PromptConfig,
        rulesSearchToolService: RulesSearchToolService,
    ): ChatClient = chatClientBuilder
        .defaultSystem(promptConfig.rulesAgentSystemPrompt)
        .defaultTools(rulesSearchToolService)
        .build()

    // Voice-transform-only client: takes a finished, already-correct answer from
    // aimuroChatClient and rewrites it in AiMuro's persona. No tools attached — it never
    // reasons about the question itself, only rewords the answer it's handed. Currently unused
    // (voice-transform stage commented out in AgenticChatOrchestrator); rides the same
    // autoconfigured chatClientBuilder as aimuroChatClient, so it follows the same profile
    // switch.
    @Bean
    @CharacterChatClient
    fun characterChatClient(
        chatClientBuilder: ChatClient.Builder,
        promptConfig: PromptConfig,
    ): ChatClient = chatClientBuilder
        .defaultSystem(promptConfig.characterSystemPrompt)
        .build()
}

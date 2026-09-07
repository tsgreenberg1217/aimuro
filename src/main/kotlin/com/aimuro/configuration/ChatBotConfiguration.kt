package com.aimuro.configuration

import com.aimuro.configuration.prompt.PromptConfig
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Qualifier
annotation class PlannerChatClient

@Qualifier
annotation class CharacterChatClient

@Configuration
class ChatBotConfiguration {

    // Manually built so the planner stays on local Ollama even when spring.ai.model.chat is
    // "openai" (aimuroChatClient on OpenAI) — that property is a single global switch that can
    // only produce one autoconfigured ChatModel for the whole app, so there's no way to get the
    // planner onto a *different* provider than aimuroChatClient through the switch alone. Reuses
    // spring.ai.ollama.* (set unconditionally in application.yaml) rather than new keys.
    // defaultCandidate = false keeps this out of unqualified ChatModel autowiring (Spring
    // resolves by assignability, not by this method's declared return type — OllamaChatModel
    // still *is* a ChatModel, so without this flag it collides with whichever provider's
    // autoconfigured ChatModel bean is active, e.g. openAiChatModel under the openai profile,
    // or the autoconfigured ollamaChatModel bean under the ollama profile) while still being
    // explicitly resolvable via @Qualifier below.
    @Bean(defaultCandidate = false)
    fun plannerOllamaChatModel(
        @Value("\${spring.ai.ollama.base-url}") baseUrl: String,
        @Value("\${spring.ai.ollama.chat.options.model}") model: String,
    ): OllamaChatModel {
        val ollamaApi = OllamaApi.builder()
            .baseUrl(baseUrl)
            .build()

        return OllamaChatModel.builder()
            .ollamaApi(ollamaApi)
            .defaultOptions(
                OllamaOptions.builder()
                    .model(model)
                    .build()
            )
            .build()
    }

    // Dedicated low-overhead client for QueryPlannerService's structured-output planning call —
    // deliberately separate from the primary client so the planner never has tools attached to
    // it and can't itself get pulled into a tool-calling loop. Always local Ollama
    // (plannerOllamaChatModel above), independent of which provider aimuroChatClient is on: the
    // one-shot classification call already works fine there, no reason to pay for OpenAI on
    // every turn just for planning.
    @Bean
    @PlannerChatClient
    fun plannerChatClient(
        @Qualifier("plannerOllamaChatModel") plannerOllamaChatModel: OllamaChatModel,
        promptConfig: PromptConfig,
    ): ChatClient = ChatClient.builder(plannerOllamaChatModel)
        .defaultSystem(promptConfig.plannerSystemPrompt)
        .build()

    @Bean
    @Primary
    fun aimuroChatClient(
        chatClientBuilder: ChatClient.Builder,
        promptConfig: PromptConfig,
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
        return chatClientBuilder
            .defaultSystem(promptConfig.systemPrompt)
            .build()
    }

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

package com.aimuro.configuration

import com.aimuro.configuration.prompt.PromptConfig
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Qualifier
annotation class PlannerChatClient

@Configuration
class ChatBotConfiguration {

    // Dedicated low-overhead client for QueryPlannerService's structured-output planning call —
    // deliberately separate from the primary client so the planner never has tools attached
    // to it and can't itself get pulled into a tool-calling loop.
    @Bean
    @PlannerChatClient
    fun plannerChatClient(
        chatClientBuilder: ChatClient.Builder,
        promptConfig: PromptConfig,
    ): ChatClient = chatClientBuilder
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
        return chatClientBuilder
            .defaultSystem(promptConfig.systemPrompt)
            .build()
    }
}

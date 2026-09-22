package com.aimuro.configuration.prompt

interface PromptConfig {
    val systemPrompt: String
    val plannerSystemPrompt: String
    val rulesComplexitySystemPrompt: String
    val characterSystemPrompt: String
    val rulesAgentSystemPrompt: String
}

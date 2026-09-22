package com.aimuro.configuration.prompt

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class DefaultPromptConfig : PromptConfig {

    override val systemPrompt: String by lazy {
        readPromptResource("prompts/system-prompt.md")
    }

    override val plannerSystemPrompt: String by lazy {
        readPromptResource("prompts/planner-system-prompt.md")
    }

    override val rulesComplexitySystemPrompt: String by lazy {
        readPromptResource("prompts/rules-complexity-prompt.md")
    }

    override val characterSystemPrompt: String by lazy {
        readPromptResource("prompts/character-prompt.md")
    }

    override val rulesAgentSystemPrompt: String by lazy {
        readPromptResource("prompts/rules-agent-system-prompt.md")
    }

    private fun readPromptResource(path: String): String =
        ClassPathResource(path).getContentAsString(Charsets.UTF_8).trim()
}

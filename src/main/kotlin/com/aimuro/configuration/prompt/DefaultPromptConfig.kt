package com.aimuro.configuration.prompt

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class DefaultPromptConfig : PromptConfig {

    override val systemPrompt: String by lazy {
        readPromptResource("prompts/system-prompt.txt")
    }

    override val plannerSystemPrompt: String by lazy {
        readPromptResource("prompts/planner-system-prompt.txt")
    }

    private fun readPromptResource(path: String): String =
        ClassPathResource(path).getContentAsString(Charsets.UTF_8).trim()
}

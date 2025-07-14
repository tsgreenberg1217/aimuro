package com.aimuro.aimuro

import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.stereotype.Service

//@Service
class GundamAdvisor(val advisor: QuestionAnswerAdvisor) : BaseAdvisor by advisor {

    override fun before(chatClientRequest: ChatClientRequest, advisorChain: AdvisorChain): ChatClientRequest {
        val mutatedRequest = advisor.before(chatClientRequest, advisorChain)
        println("GundamAdvisor: Before processing request: ${mutatedRequest.prompt}")
        return mutatedRequest
    }

}
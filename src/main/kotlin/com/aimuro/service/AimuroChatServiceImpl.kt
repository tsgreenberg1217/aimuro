package com.aimuro.service

import com.aimuro.configuration.promptTemplate
import com.aimuro.controller.ChatMessage
import com.aimuro.controller.ChatRequest
import com.aimuro.controller.RulesResponse
import com.aimuro.history.ChatResponse
import com.aimuro.repository.ChatHistoryRepository
import com.aimuro.history.ChatStreamConsumer
import com.aimuro.history.ChatStreamProducer
import com.aimuro.repository.ConversationRepository
import com.aimuro.history.StreamBufferService
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.UUID

@Service
@Profile("!debug")
class AimuroChatServiceImpl(
    private val chatClient: ChatClient,
    private val streamBufferService: StreamBufferService,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val conversationRepository: ConversationRepository,
    private val chatStreamProducer: ChatStreamProducer,
    private val chatStreamConsumer: ChatStreamConsumer
) : AimuroChatService {

    companion object {
        val logger = LoggerFactory.getLogger(AimuroChatServiceImpl::class.java)
    }

    override fun ask(request: ChatRequest): String {
        logger.info("Received ask request for conversationId: ${request.conversationId}")
        val requestId = UUID.randomUUID().toString()
        Thread.startVirtualThread { generateAsync(requestId, request.conversationId, request.conversation) }
        return requestId
    }

    override fun replay(requestId: String): Flux<RulesResponse> {
        logger.info("Received replay request for requestId: $requestId")
        val status = streamBufferService.getStatus(requestId)
        return when (status) {
            "done" -> {
                logger.info("No active stream found for requestId: $requestId, fetching from history")
                val history = conversationRepository.findById(0)
                if (!history.isPresent) Flux.error(ResponseStatusException(HttpStatus.NOT_FOUND))
                else Flux.just(
                    RulesResponse(
                        history.get().responses.lastOrNull()?.response ?: "No response found"
                    )
                )
            }

            "error" -> {
                logger.info("Stream for requestId: $requestId is in error state")
                Flux.error(ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR))
            }

            else -> {
                logger.info("Consuming stream for requestId: $requestId with status: $status")
                chatStreamConsumer.consume(requestId)
            }
        }
    }

    override fun getHistory(conversationId: Long): List<ChatResponse> =
        chatHistoryRepository.findByConversationId(conversationId)

    override fun getConversationStatus(conversationId: String): Map<String, String> {
        val requestId = streamBufferService.getActiveRequestId(conversationId)
            ?: return mapOf("status" to "idle")
        val status = streamBufferService.getStatus(requestId)
            ?: return mapOf("status" to "idle")
        return mapOf("status" to status, "requestId" to requestId)
    }

    private fun generateAsync(requestId: String, conversationId: Long, conversation: List<ChatMessage>) {
        logger.info("Generating async response for requestId: $requestId, conversationId: $conversationId")
        streamBufferService.setStatus(requestId, "in_progress")
        streamBufferService.setActiveRequest(conversationId, requestId)
        val messages = conversation.map { msg ->
            if (msg.role == "user") UserMessage(msg.content) else AssistantMessage(msg.content)
        }
        val chunks = mutableListOf<String>()
        chatClient
            .prompt(promptTemplate.template)
            .messages(messages)
            .user(conversation.last().content)
            .stream()
            .content()
            .doOnNext { chunk ->
                chatStreamProducer.appendChunk(requestId, chunk)
                chunks.add(chunk)
            }
            .doOnComplete {
                logger.info("Response generation complete for requestId: $requestId, total chunks: ${chunks.size}")
                try {
                    val conversation = conversationRepository.findById(conversationId)
                    chatHistoryRepository.save(
                        ChatResponse(
                            conversation = conversation.get(),
                            response = chunks.joinToString("")
                        )
                    )
                } catch (e: Exception) {
                    // Log the error and continue, since the main response generation is successful
                    logger.info("Failed to save chat history: ${e.message}")
                }
                streamBufferService.setStatus(requestId, "complete")
                chatStreamProducer.appendDone(requestId)
                streamBufferService.expire(requestId, conversationId, Duration.ofMinutes(10))
            }
            .doOnError {
                with(streamBufferService) {
                    setStatus(requestId, "error")
                    expire(requestId, conversationId, Duration.ofMinutes(10))
                }

            }
            .blockLast()
    }
}

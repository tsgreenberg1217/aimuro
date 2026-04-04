package com.aimuro.service

import com.aimuro.configuration.promptTemplate
import com.aimuro.controller.ChatRequest
import com.aimuro.controller.RulesResponse
import com.aimuro.history.ChatResponse
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Service
@Profile("debug")
class DebugAimuroChatServiceImpl(
    private val chatClient: ChatClient
) : AimuroChatService {

    private val chunkStore = ConcurrentHashMap<String, MutableList<String>>()
    private val statusStore = ConcurrentHashMap<String, String>()

    override fun ask(request: ChatRequest): String {
        val requestId = UUID.randomUUID().toString()
        chunkStore[requestId] = Collections.synchronizedList(mutableListOf())
        statusStore[requestId] = "in_progress"
        Thread.startVirtualThread { generateAsync(requestId, request) }
        return requestId
    }

    override fun replay(requestId: String): Flux<RulesResponse> {
        if (!statusStore.containsKey(requestId)) return Flux.error(ResponseStatusException(HttpStatus.NOT_FOUND))
        return replayFromMemory(requestId)
    }

    override fun getHistory(conversationId: Long): List<ChatResponse> = emptyList()

    override fun getConversationStatus(conversationId: String): Map<String, String> =
        mapOf("status" to "idle")

    private fun generateAsync(requestId: String, request: ChatRequest) {
        val messages = request.conversation.map { msg ->
            if (msg.role == "user") UserMessage(msg.content) else AssistantMessage(msg.content)
        }
        chatClient
            .prompt(promptTemplate.template)
            .messages(messages)
            .user(request.conversation.last().content)
            .stream()
            .content()
            .doOnNext { chunk -> chunkStore[requestId]?.add(chunk) }
            .doOnComplete { statusStore[requestId] = "complete" }
            .doOnError { statusStore[requestId] = "error" }
            .blockLast()
    }

private fun replayFromMemory(requestId: String): Flux<RulesResponse> {
        val offset = AtomicInteger(0)
        return Flux.create { sink ->
            val disposable = Flux.interval(Duration.ofMillis(200)).subscribe { _ ->
                if (sink.isCancelled) return@subscribe
                val allChunks = chunkStore[requestId] ?: emptyList()
                val currentOffset = offset.get()
                val newChunks = allChunks.drop(currentOffset)
                offset.set(allChunks.size)
                newChunks.forEach { chunk -> sink.next(RulesResponse(chunk)) }
                when (statusStore[requestId]) {
                    "error" -> sink.error(ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR))
                    "complete" -> if (offset.get() >= allChunks.size) sink.complete()
                }
            }
            sink.onDispose { disposable.dispose() }
        }
    }
}

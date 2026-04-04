package com.aimuro.history

import com.aimuro.configuration.ChatReceiver
import com.aimuro.controller.RulesResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

@Service
@Profile("!debug")
class ChatStreamConsumer(val receiver: ChatReceiver) {

    companion object {
        val logger = LoggerFactory.getLogger(ChatStreamConsumer::class.java)
    }

    fun consume(requestId: String): Flux<RulesResponse> {
        logger.info("Starting to consume stream for requestId: $requestId")
        return receiver.receive(StreamOffset.fromStart("stream:$requestId"))
            .takeWhile { message ->
                logger.info("message is ${message.value}, done: ${message.value["done"]}")
                message.value["done"] != "true"
            }
            .mapNotNull { message -> message.value["chunk"]?.let { RulesResponse(it, isComplete = false) } }
            .concatWith(Flux.just(RulesResponse("", isComplete = true)))
            .doOnComplete {
                logger.info("Consumer done: completed for requestId: $requestId")
            }
    }

}

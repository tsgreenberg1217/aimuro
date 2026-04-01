package com.aimuro.history

import com.aimuro.configuration.ChatReceiver
import com.aimuro.controller.RulesResponse
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

@Service
@Profile("!debug")
class ChatStreamConsumer(val receiver: ChatReceiver) {

    fun consume(requestId: String): Flux<RulesResponse> =
        receiver.receive(StreamOffset.fromStart("stream:$requestId"))
            .takeWhile { message -> message.value["done"] != "true" }
            .mapNotNull { message -> message.value["chunk"]?.let { RulesResponse(it) } }
}

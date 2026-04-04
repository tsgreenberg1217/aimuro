package com.aimuro.history

import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
@Profile("!debug")
class ChatStreamProducer(private val redisTemplate: StringRedisTemplate) {

    fun appendChunk(requestId: String, chunk: String) = StreamRecords
        .string(mapOf("chunk" to chunk))
        .withStreamKey("stream:$requestId")
        .run(redisTemplate.opsForStream<String, String>()::add)

    fun appendDone(requestId: String) = StreamRecords
        .string(mapOf("done" to "true"))
        .withStreamKey("stream:$requestId")
        .run(redisTemplate.opsForStream<String, String>()::add)
}

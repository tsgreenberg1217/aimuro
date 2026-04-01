package com.aimuro.history

import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

@Service
@Profile("!debug")
class StreamBufferService(private val redisTemplate: StringRedisTemplate) {

    fun setStatus(requestId: String, status: String) {
        redisTemplate.opsForValue().set("stream:$requestId:status", status)
    }

    fun getStatus(requestId: String): String? {
        return redisTemplate.opsForValue().get("stream:$requestId:status")
    }

    fun setActiveRequest(conversationId: Long, requestId: String) {
        redisTemplate.opsForValue().set("conversation:$conversationId:active", requestId)
    }

    fun getActiveRequestId(conversationId: String): String? {
        return redisTemplate.opsForValue().get("conversation:$conversationId:active")
    }

    fun expire(requestId: String, conversationId: Long, duration: Duration) {
        redisTemplate.expire("stream:$requestId", duration)
        redisTemplate.expire("stream:$requestId:status", duration)
        redisTemplate.expire("conversation:$conversationId:active", duration)
    }
}

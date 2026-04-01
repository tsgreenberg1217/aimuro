package com.aimuro.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.stream.StreamReceiver
import org.springframework.data.redis.stream.StreamReceiver.StreamReceiverOptions
import java.time.Duration


typealias ChatReceiver = StreamReceiver<String, MapRecord<String, String, String>>
@Profile("!debug")
@Configuration
class RedisConfiguration {
    @Bean
    fun redisReceiver(
        connectionFactory: ReactiveRedisConnectionFactory
    ): ChatReceiver =
        StreamReceiver.create(
            connectionFactory,
            StreamReceiverOptions.builder()
                .pollTimeout(Duration.ofMillis(100))
                .build()
        )
}
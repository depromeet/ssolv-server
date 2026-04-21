package org.depromeet.team3.common.config

import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories
import java.time.Duration

/**
 * Redis 인프라 설정을 담당하는 클래스
 *
 * 주요 역할:
 * 1. Redis 연결 및 메시지 리스너 컨테이너 설정
 * 2. 캐시 및 메시지 큐 처리를 위한 StringRedisTemplate 빈 등록
 * 3. Lettuce 소켓 keepalive 설정으로 Redis Stream 연결 안정성 확보
 */
@Configuration
@org.springframework.context.annotation.Profile("!test")
@EnableRedisRepositories(
    basePackageClasses = [RedisConfig::class],
    enableKeyspaceEvents = org.springframework.data.redis.core.RedisKeyValueAdapter.EnableKeyspaceEvents.ON_STARTUP,
)
class RedisConfig {

    @Bean
    fun lettuceClientConfigurationBuilderCustomizer(): LettuceClientConfigurationBuilderCustomizer =
        LettuceClientConfigurationBuilderCustomizer { builder ->
            builder.clientOptions(
                ClientOptions.builder()
                    .autoReconnect(true)
                    .socketOptions(
                        SocketOptions.builder()
                            .keepAlive(
                                SocketOptions.KeepAliveOptions.builder()
                                    .enable()
                                    .idle(Duration.ofMinutes(5))
                                    .interval(Duration.ofSeconds(30))
                                    .count(3)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
        }

    @Bean
    fun redisMessageListenerContainer(redisConnectionFactory: RedisConnectionFactory): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(redisConnectionFactory)
        return container
    }

    @Bean
    fun stringRedisTemplate(redisConnectionFactory: RedisConnectionFactory): org.springframework.data.redis.core.StringRedisTemplate =
        org.springframework.data.redis.core.StringRedisTemplate(redisConnectionFactory)
}

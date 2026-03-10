package org.depromeet.team3.config

import io.sentry.protocol.User
import io.sentry.spring.jakarta.SentryUserProvider
import org.depromeet.team3.security.jwt.JwtAuthenticationToken
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Sentry에서 사용자 정보를 수집하기 위한 설정 클래스
 */
@Configuration
class SentryConfig {
    private val logger = org.slf4j.LoggerFactory.getLogger(SentryConfig::class.java)

    init {
        logger.info("Sentry Configuration initialized and enabled.")
    }

    /**
     * SecurityContext에서 사용자 ID를 추출하여 Sentry에 전달하는 Provider
     */
    @Bean
    fun sentryUserProvider(): SentryUserProvider {
        return SentryUserProvider {
            val authentication = SecurityContextHolder.getContext().authentication
            if (authentication is JwtAuthenticationToken) {
                User().apply {
                    id = authentication.principal.toString()
                }
            } else {
                null
            }
        }
    }
}
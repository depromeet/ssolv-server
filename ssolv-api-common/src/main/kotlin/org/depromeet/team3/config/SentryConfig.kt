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
    fun sentryUserProvider(): SentryUserProvider = SentryUserProvider {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken) {
            User().apply {
                id = authentication.principal.toString()
            }
        } else {
            null
        }
    }

    /**
     * Sentry로 성능 트랜잭션 전송 전 데이터를 필터링하는 콜백
     * 프로메테우스 스크래핑 및 상태 체크용 /actuator 요청을 무시합니다.
     */
    @Bean
    fun beforeSendTransactionCallback(): io.sentry.SentryOptions.BeforeSendTransactionCallback =
        io.sentry.SentryOptions.BeforeSendTransactionCallback {
                transaction,
                _,
            ->
            val name = transaction.transaction
            if (name != null && name.contains("/actuator")) {
                null // null을 반환하면 해당 데이터는 전송되지 않습니다 (드롭)
            } else {
                transaction
            }
        }
}

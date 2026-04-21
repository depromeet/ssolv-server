package org.depromeet.team3.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * 테스트용 Security 설정 - 인증을 완전히 비활성화
 * @UserId 어노테이션을 사용하는 테스트에서 인증 없이 테스트할 수 있도록 함
 */
@TestConfiguration
@EnableWebSecurity
class TestSecurityConfig {

    @Bean
    @Primary
    fun testSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf -> csrf.disable() }
            .authorizeHttpRequests { authz ->
                authz.anyRequest().permitAll()
            }

        return http.build()
    }
}

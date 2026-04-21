package org.depromeet.team3.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.depromeet.team3.common.resolver.UserIdArgumentResolver
import org.depromeet.team3.security.jwt.AppProperties
import org.depromeet.team3.security.jwt.JwtProperties
import org.depromeet.team3.security.jwt.JwtTokenProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.MethodParameter
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Security 관련 테스트용 설정 클래스
 * @WebMvcTest에서 SecurityConfig를 사용할 때 필요한 의존성들을 Mock으로 제공
 */
@TestConfiguration
@EnableWebSecurity
class SecurityTestConfig {

    @Bean
    @Primary
    fun testJwtProperties(): JwtProperties = JwtProperties(
        secret = "test-secret-key-for-jwt-token-generation-minimum-256-bits-long",
        accessTokenValidity = 3600000L, // 1시간
        refreshTokenValidity = 604800000L, // 1주일
    )

    @Bean
    @Primary
    fun testAppProperties(): AppProperties = AppProperties(
        env = "test",
    )

    @Bean
    @Primary
    fun testPasswordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    @Primary
    fun testObjectMapper(): ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())

    @Bean
    @Primary
    fun testJwtTokenProvider(jwtProperties: JwtProperties): JwtTokenProvider = JwtTokenProvider(jwtProperties)

    @Bean
    @Primary
    fun testSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }

    @Bean
    @Primary
    fun testUserIdArgumentResolver(): UserIdArgumentResolver {
        return object : UserIdArgumentResolver() {
            override fun resolveArgument(
                parameter: MethodParameter,
                mavContainer: ModelAndViewContainer?,
                webRequest: NativeWebRequest,
                binderFactory: WebDataBinderFactory?,
            ): Any? {
                // 테스트에서는 항상 사용자 ID 1을 반환
                val isNullable = parameter.parameterType == Long::class.javaObjectType ||
                    parameter.parameterAnnotations.any { it.annotationClass.simpleName == "Nullable" }

                return if (isNullable) {
                    1L
                } else {
                    1L
                }
            }
        }
    }

    @Bean
    @Primary
    fun testWebMvcConfigurer(userIdArgumentResolver: UserIdArgumentResolver): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
            resolvers.add(userIdArgumentResolver)
        }
    }
}

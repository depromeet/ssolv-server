package org.depromeet.team3.config

import org.depromeet.team3.common.annotation.UserId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * 테스트용 UserIdArgumentResolver
 * 실제 JWT 토큰 없이도 테스트용 사용자 ID를 반환
 */
@TestConfiguration
class TestUserIdArgumentResolver : HandlerMethodArgumentResolver {

    // 테스트용 기본 사용자 ID
    private var testUserId: Long = 1L

    /**
     * 테스트용 사용자 ID 설정
     */
    fun setTestUserId(userId: Long) {
        this.testUserId = userId
    }

    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.hasParameterAnnotation(UserId::class.java) &&
        (
            parameter.parameterType == Long::class.java ||
                parameter.parameterType == Long::class.javaObjectType
            )

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? {
        val isNullable = parameter.parameterType == Long::class.javaObjectType ||
            parameter.parameterAnnotations.any { it.annotationClass.simpleName == "Nullable" }

        return if (isNullable) {
            testUserId
        } else {
            testUserId
        }
    }

    @Bean
    @Primary
    fun testUserIdArgumentResolver(): TestUserIdArgumentResolver = this
}

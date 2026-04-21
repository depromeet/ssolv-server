package org.depromeet.team3.common.resolver

import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.common.annotation.UserId
import org.depromeet.team3.security.jwt.JwtAuthenticationToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.MethodParameter
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.NativeWebRequest

/**
 * @UserId ArgumentResolver 핵심 기능 테스트
 */
class UserIdArgumentResolverTest {

    private lateinit var resolver: UserIdArgumentResolver
    private lateinit var webRequest: NativeWebRequest

    @BeforeEach
    fun setUp() {
        resolver = UserIdArgumentResolver()
        webRequest = mock(NativeWebRequest::class.java)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `@UserId 어노테이션이 있는 Long 파라미터를 지원한다`() {
        // given
        val methodParameter = mock(MethodParameter::class.java)
        `when`(methodParameter.hasParameterAnnotation(UserId::class.java)).thenReturn(true)
        `when`(methodParameter.parameterType).thenReturn(Long::class.java)

        // when
        val supports = resolver.supportsParameter(methodParameter)

        // then
        assertThat(supports).isTrue
    }

    @Test
    fun `@UserId 어노테이션이 없는 파라미터는 지원하지 않는다`() {
        // given
        val methodParameter = mock(MethodParameter::class.java)
        `when`(methodParameter.hasParameterAnnotation(UserId::class.java)).thenReturn(false)

        // when
        val supports = resolver.supportsParameter(methodParameter)

        // then
        assertThat(supports).isFalse
    }

    @Test
    fun `JWT 토큰에서 사용자 ID를 정상적으로 추출한다`() {
        // given
        val userId = 123L
        val authorities = listOf(SimpleGrantedAuthority("ROLE_USER"))
        val authentication = JwtAuthenticationToken(userId, authorities)
        SecurityContextHolder.getContext().authentication = authentication

        val methodParameter = mock(MethodParameter::class.java)

        // when
        val result = resolver.resolveArgument(methodParameter, null, webRequest, null)

        // then
        assertThat(result).isEqualTo(userId)
    }

    @Test
    fun `인증되지 않은 경우 nullable 파라미터에서 null을 반환한다`() {
        // given
        val methodParameter = mock(MethodParameter::class.java)
        `when`(methodParameter.parameterType).thenReturn(Long::class.javaObjectType)
        `when`(methodParameter.parameterAnnotations).thenReturn(emptyArray())

        SecurityContextHolder.getContext().authentication = null

        // when
        val result = resolver.resolveArgument(methodParameter, null, webRequest, null)

        // then
        assertThat(result).isNull()
    }
}

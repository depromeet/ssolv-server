package org.depromeet.team3.security.jwt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority

class JwtAuthenticationTokenTest {

    @Test
    fun `JwtAuthenticationToken 생성 및 정보 확인`() {
        // given
        val userId = 123L
        val authorities = listOf(SimpleGrantedAuthority("ROLE_USER"))

        // when
        val token = JwtAuthenticationToken(userId, authorities)

        // then
        assertThat(token.principal).isEqualTo(userId)
        assertThat(token.credentials).isNull()
        assertThat(token.getUserId()).isEqualTo(userId)
        assertThat(token.authorities).hasSize(1)
        assertThat(token.authorities.first().authority).isEqualTo("ROLE_USER")
        assertThat(token.isAuthenticated).isTrue
    }

    @Test
    fun `userId가 null인 경우 처리`() {
        // given
        val userId: Long? = null
        val authorities = listOf(SimpleGrantedAuthority("ROLE_USER"))

        // when
        val token = JwtAuthenticationToken(userId, authorities)

        // then
        assertThat(token.principal).isNull()
        assertThat(token.getUserId()).isNull()
        assertThat(token.isAuthenticated).isTrue
    }

    @Test
    fun `권한이 없는 경우 처리`() {
        // given
        val userId = 123L
        val authorities = emptyList<SimpleGrantedAuthority>()

        // when
        val token = JwtAuthenticationToken(userId, authorities)

        // then
        assertThat(token.principal).isEqualTo(userId)
        assertThat(token.authorities).isEmpty()
        assertThat(token.isAuthenticated).isTrue
    }
}

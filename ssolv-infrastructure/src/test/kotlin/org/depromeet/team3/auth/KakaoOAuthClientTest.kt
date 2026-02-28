package org.depromeet.team3.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.auth.model.KakaoResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.auth.client.KakaoOAuthClient
import org.depromeet.team3.auth.properties.KakaoProperties
import org.mockito.kotlin.mock
import kotlinx.coroutines.runBlocking

class KakaoOAuthClientTest {

    @Test
    fun `허용되지 않은 redirect_uri로 토큰 요청시 예외가 발생한다`() {
        // given
        val kakaoProperties = KakaoProperties().apply { clientId = "test-client-id" }
        val kakaoOAuthClient = KakaoOAuthClient(ObjectMapper(), kakaoProperties, mock())
        val invalidRedirectUri = "http://invalid-uri.com"
        val accessCode = "test-access-code"

        // when & then
        val exception = assertThrows<AuthException> {
            runBlocking { kakaoOAuthClient.requestToken(accessCode, invalidRedirectUri) }
        }
        
        assertThat(exception.errorCode.code).isEqualTo("O008")
    }

    @Test
    fun `null oAuthToken으로 프로필 요청시 예외가 발생한다`() {
        // given
        val kakaoProperties = KakaoProperties().apply { clientId = "test-client-id" }
        val kakaoOAuthClient = KakaoOAuthClient(ObjectMapper(), kakaoProperties, mock())

        // when & then
        val exception = assertThrows<AuthException> {
            runBlocking { kakaoOAuthClient.requestProfile(null) }
        }
        
        assertThat(exception.errorCode.code).isEqualTo("O002")
    }

    @Test
    fun `잘못된 redirect_uri는 trim 후에도 허용되지 않는다`() {
        // given
        val kakaoProperties = KakaoProperties().apply { clientId = "test-client-id" }
        val kakaoOAuthClient = KakaoOAuthClient(ObjectMapper(), kakaoProperties, mock())
        val invalidRedirectUriWithSpaces = "  http://invalid-uri.com  "
        val accessCode = "test-access-code"

        // when & then
        val exception = assertThrows<AuthException> {
            runBlocking { kakaoOAuthClient.requestToken(accessCode, invalidRedirectUriWithSpaces) }
        }
        
        assertThat(exception.errorCode.code).isEqualTo("O008")
    }

    @Test
    fun `올바른 토큰 구조로 프로필 요청시 null 체크를 통과한다`() {
        // given
        val kakaoProperties = KakaoProperties().apply { clientId = "test-client-id" }
        val kakaoOAuthClient = KakaoOAuthClient(ObjectMapper(), kakaoProperties, mock())
        val oAuthToken = KakaoResponse.OAuthToken(
            access_token = "valid-access-token"
        )

        // when & then
        val exception = assertThrows<Exception> {
            runBlocking { kakaoOAuthClient.requestProfile(oAuthToken) }
        }
        
        assertThat(exception.message).doesNotContain("access_token")
    }
}

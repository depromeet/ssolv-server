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
import org.depromeet.team3.common.util.CoroutineDispatchers
import org.mockito.kotlin.whenever
import org.mockito.Mockito.lenient
import org.mockito.quality.Strictness
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.junit.jupiter.MockitoExtension
import org.junit.jupiter.api.extension.ExtendWith
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KakaoOAuthClientTest {
    
    private lateinit var coroutineDispatchers: CoroutineDispatchers
    private lateinit var kakaoProperties: KakaoProperties
    private lateinit var kakaoOAuthClient: KakaoOAuthClient

    @BeforeEach
    fun setUp() {
        coroutineDispatchers = mock()
        
        kakaoProperties = KakaoProperties().apply { clientId = "test-client-id" }
        kakaoOAuthClient = KakaoOAuthClient(
            objectMapper = ObjectMapper(),
            kakaoProperties = kakaoProperties,
            restTemplate = mock(),
            coroutineDispatchers = coroutineDispatchers
        )
    }

    @Test
    fun `허용되지 않은 redirect_uri로 토큰 요청시 예외가 발생한다`() {
        runTest {
            lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher(testScheduler))
            // given
            val invalidRedirectUri = "http://invalid-uri.com"
            val accessCode = "test-access-code"

            // when & then
            try {
                kakaoOAuthClient.requestToken(accessCode, invalidRedirectUri)
                org.junit.jupiter.api.fail("Should have thrown AuthException")
            } catch (e: AuthException) {
                assertThat(e.errorCode.code).isEqualTo("O008")
            }
        }
    }

    @Test
    fun `null oAuthToken으로 프로필 요청시 예외가 발생한다`() {
        runTest {
            lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher(testScheduler))
            // given
            // Already initialized in setUp

            // when & then
            try {
                kakaoOAuthClient.requestProfile(null)
                org.junit.jupiter.api.fail("Should have thrown AuthException")
            } catch (e: AuthException) {
                assertThat(e.errorCode.code).isEqualTo("O002")
            }
        }
    }

    @Test
    fun `잘못된 redirect_uri는 trim 후에도 허용되지 않는다`() {
        runTest {
            lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher(testScheduler))
            // given
            val invalidRedirectUriWithSpaces = "  http://invalid-uri.com  "
            val accessCode = "test-access-code"

            // when & then
            val exception = assertThrows<AuthException> {
                runBlocking {
                    kakaoOAuthClient.requestToken(accessCode, invalidRedirectUriWithSpaces)
                }
            }
            
            assertThat(exception.errorCode.code).isEqualTo("O008")
        }
    }

    @Test
    fun `올바른 토큰 구조로 프로필 요청시 null 체크를 통과한다`() {
        runTest {
            lenient().whenever(coroutineDispatchers.VT).thenReturn(UnconfinedTestDispatcher(testScheduler))
            // given
            val oAuthToken = KakaoResponse.OAuthToken(
                access_token = "valid-access-token"
            )

            // when & then
            val exception = assertThrows<Exception> {
                runBlocking {
                    kakaoOAuthClient.requestProfile(oAuthToken)
                }
            }
            
            assertThat(exception.message).doesNotContain("access_token")
        }
    }
}

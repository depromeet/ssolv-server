package org.depromeet.team3.auth

import org.assertj.core.api.Assertions.assertThat
import org.depromeet.team3.auth.properties.KakaoProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest(classes = [KakaoProperties::class])
@EnableConfigurationProperties(KakaoProperties::class)
@TestPropertySource(
    properties = [
        "kakao.client-id=test-client-id",
    ],
)
class KakaoPropertiesTest {

    @Autowired
    private lateinit var kakaoProperties: KakaoProperties

    @Test
    fun `카카오 프로퍼티가 올바르게 바인딩된다`() {
        // when & then
        assertThat(kakaoProperties.clientId).isEqualTo("test-client-id")
    }

    @Test
    fun `clientId는 비어있지 않아야 한다`() {
        // when & then
        assertThat(kakaoProperties.clientId).isNotEmpty()
    }
}

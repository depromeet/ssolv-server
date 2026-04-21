package org.depromeet.team3.auth.command

data class KakaoLoginCommand(val authorizationCode: String, val redirectUri: String? = null) {
    init {
        require(authorizationCode.isNotBlank()) { "인가 코드는 필수입니다" }
    }
}

package org.depromeet.team3.auth.command

data class RefreshTokenCommand(val refreshToken: String) {
    init {
        require(refreshToken.isNotBlank()) { "Refresh Token은 필수입니다" }
    }
}

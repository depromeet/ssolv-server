package org.depromeet.team3.util

/**
 * 모임 ID와 토큰 간의 변환 로직을 담당하는 유틸리티
 */
object MeetingIdParser {
    private const val SEPARATOR = ":"

    /**
     * 입력값이 숫자면 Long으로 변환하고, 그렇지 않으면 초대 토큰으로 간주하여 디코딩합니다.
     * 초대 URL 형태(https://.../token=xxx)로 들어올 경우 토큰 부분만 추출하여 처리합니다.
     */
    fun parse(identifier: String): Long {
        val token = if (identifier.startsWith("http")) {
            if (!identifier.contains("token=")) {
                throw IllegalArgumentException("Invalid invitation URL: missing token parameter")
            }
            identifier.substringAfter("token=")
        } else {
            identifier
        }
        return token.toLongOrNull() ?: parseToken(token)
    }

    private fun parseToken(token: String): Long {
        val decoded = DataEncoder.decodeWithSeparator(token, SEPARATOR)
            ?: throw IllegalArgumentException("Invalid token format")

        return decoded.firstOrNull()?.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid meeting id in token")
    }
}

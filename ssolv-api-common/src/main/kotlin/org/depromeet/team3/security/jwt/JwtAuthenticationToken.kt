package org.depromeet.team3.security.jwt

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority

/**
 * JWT 기반 인증 토큰
 * 액세스 토큰 검증 및 인증 여부 판단에 사용
 */
class JwtAuthenticationToken(private val userId: Long?, authorities: Collection<GrantedAuthority?>?) :
    AbstractAuthenticationToken(authorities) {

    private val credentials: String? = null

    init {
        // 인증 완료 상태로 설정
        super.setAuthenticated(true)
    }

    /**
     * 인증된 사용자의 ID를 반환
     */
    override fun getPrincipal(): Any? = userId

    /**
     * 인증 자격 증명 반환 (JWT에서는 사용하지 않으므로 null)
     */
    override fun getCredentials(): Any? = credentials

    /**
     * 사용자 ID를 편리하게 가져오기 위한 헬퍼 메서드
     */
    fun getUserId(): Long? = userId
}

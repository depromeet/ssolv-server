package org.depromeet.team3.security.jwt

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.depromeet.team3.auth.exception.AuthException
import org.depromeet.team3.common.exception.ErrorCode
import org.depromeet.team3.common.resolver.UserIdArgumentResolver
import org.depromeet.team3.common.response.DpmApiResponse
import org.slf4j.MDC
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {
    
    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // 1. 제외 URL 체크
        if (isExcludedUrl(request.requestURI)) {
            filterChain.doFilter(request, response)
            return
        }

        try {
            // 2. 인증 처리
            val authResult = processAuthentication(request)
            setSecurityContext(authResult)

            filterChain.doFilter(request, response)
        } catch (e: AuthException) {
            logger.warn("[401] JWT 필터 인증 실패", e)
            handleAuthException(response, e)
        } catch (e: Exception) {
            logger.error("[500] JWT 필터 처리 중 예상치 못한 오류 발생", e)
            handleInternalServerError(response)
        } finally {
            MDC.remove(UserIdArgumentResolver.USER_ID)
        }
    }

    /**
     * 인증 처리 로직
     */
    private fun processAuthentication(
        request: HttpServletRequest
    ): AuthResult {
        val accessToken = jwtTokenProvider.extractToken(request)
            ?: return AuthResult.Failed

        return when {
            // 유효한 Access Token이 있는 경우
            jwtTokenProvider.validateAccessToken(accessToken) -> {
                val userId = extractUserId(accessToken)
                    ?: throw AuthException(ErrorCode.TOKEN_USER_ID_INVALID)
                AuthResult.Success(userId)
            }
            
            // Access Token이 유효하지 않은 경우
            else -> throw AuthException(ErrorCode.ACCESS_TOKEN_INVALID)
        }
    }

    /**
     * 토큰에서 사용자 ID 추출
     */
    private fun extractUserId(token: String): Long? {
        return jwtTokenProvider.getUserIdFromToken(token)?.toLongOrNull()
    }

    /**
     * Security Context 설정
     */
    private fun setSecurityContext(authResult: AuthResult) {
        when (authResult) {
            is AuthResult.Success -> {
                val authorities = listOf<GrantedAuthority>(SimpleGrantedAuthority("ROLE_USER"))
                val authentication = JwtAuthenticationToken(authResult.userId, authorities)
                SecurityContextHolder.getContext().authentication = authentication
                
                // MDC에 사용자 ID 저장 (로그 추적용)
                MDC.put(UserIdArgumentResolver.USER_ID, authResult.userId.toString())
            }
            is AuthResult.Failed -> {
                SecurityContextHolder.clearContext()
            }
        }
    }

    private fun isExcludedUrl(requestURI: String): Boolean {
        return EXCLUDED_URLS.any { prefix -> requestURI.startsWith(prefix) }
    }

    /**
     * 인증 실패 시 401 응답
     */
    private fun handleAuthException(response: HttpServletResponse, e: AuthException) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json;charset=UTF-8"

        val errorResponse = DpmApiResponse.error(e)
        val json = objectMapper.writeValueAsString(errorResponse)
        response.writer.write(json)
    }

    private fun handleInternalServerError(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
        response.contentType = "application/json;charset=UTF-8"

        val errorResponse = DpmApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR)
        val json = objectMapper.writeValueAsString(errorResponse)
        response.writer.write(json)
    }

    /**
     * 인증 결과를 나타내는 sealed class
     */
    private sealed class AuthResult {
        data class Success(val userId: Long?) : AuthResult()
        object Failed : AuthResult()
    }

    companion object {
        private val EXCLUDED_URLS = listOf(
            "/swagger-ui/**", 
            "/v3/api-docs/**", 
            "/swagger", 
            "/api/auth/kakao-login", 
            "/favicon.ico"
        )
    }
}

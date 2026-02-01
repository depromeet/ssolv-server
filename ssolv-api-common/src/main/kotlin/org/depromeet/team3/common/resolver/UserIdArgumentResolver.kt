package org.depromeet.team3.common.resolver

import org.depromeet.team3.common.annotation.UserId
import org.depromeet.team3.security.jwt.JwtAuthenticationToken
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * @UserId 어노테이션이 붙은 파라미터에 JWT 토큰에서 추출한 사용자 ID를 주입하는 ArgumentResolver
 * 
 * 사용자 ID를 MDC에 저장하여 로그 추적을 가능하게 합니다.
 */
@Component
class UserIdArgumentResolver : HandlerMethodArgumentResolver {
    
    private val logger = LoggerFactory.getLogger(UserIdArgumentResolver::class.java)
    
    companion object {
        const val USER_ID = "user_id"
    }

    /**
     * 지원하는 파라미터인지 확인
     * @UserId 어노테이션이 붙고, Long 또는 Long? 타입인 파라미터만 지원
     */
    override fun supportsParameter(parameter: MethodParameter): Boolean {
        return parameter.hasParameterAnnotation(UserId::class.java) &&
                (parameter.parameterType == Long::class.java || 
                 parameter.parameterType == Long::class.javaObjectType)
    }

    /**
     * JWT 토큰에서 사용자 ID를 추출하여 반환
     * 추출된 사용자 ID를 MDC에 저장하여 로그 추적을 가능하게 함
     */
    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): Any? {
        val authentication = SecurityContextHolder.getContext().authentication
        
        // JWT 인증 토큰이 아닌 경우
        if (authentication !is JwtAuthenticationToken) {
            MDC.put(USER_ID, "anonymous")
            return handleUnauthenticatedRequest(parameter)
        }

        val userId = authentication.getUserId()
        
        // 사용자 ID가 없는 경우
        if (userId == null) {
            MDC.put(USER_ID, "anonymous")
            return handleUnauthenticatedRequest(parameter)
        }

        // MDC에 사용자 ID 저장
        MDC.put(USER_ID, userId.toString())
        
        // 사용자 식별 로그 (MDC에 이미 포함되어 있으므로 DEBUG 레벨로 변경)
        logger.debug("User identified: userId={}", userId)

        return userId
    }

    /**
     * 인증되지 않은 요청 처리
     * - nullable 파라미터인 경우: null 반환
     * - non-null 파라미터인 경우: 예외 발생
     */
    private fun handleUnauthenticatedRequest(parameter: MethodParameter): Any? {
        val isNullable = parameter.parameterType == Long::class.javaObjectType ||
                        parameter.parameterAnnotations.any { it.annotationClass.simpleName == "Nullable" }
        
        return if (isNullable) {
            null
        } else {
            throw IllegalStateException(
                "인증된 사용자만 접근할 수 있습니다. " +
                "메서드 파라미터를 nullable(Long?)로 변경하거나 적절한 인증을 확인해주세요."
            )
        }
    }
}

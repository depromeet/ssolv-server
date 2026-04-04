package org.depromeet.team3.common.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.util.*

/**
 * MDC(Mapped Diagnostic Context)를 활용한 요청 추적 필터
 * 
 * 각 HTTP 요청마다 고유한 request_id를 생성하여 MDC에 저장하고,
 * 요청 처리 시간과 기본 정보를 로깅합니다.
 * 
 * Nginx에서 전달된 X-RequestID 헤더가 있으면 우선 사용하고,
 * 없으면 UUID 기반으로 생성합니다.
 * 이를 통해 Nginx 로그와 WAS 로그를 동일한 request_id로 추적할 수 있습니다.
 * 
 * 멀티스레드 환경에서 동일한 요청의 로그를 추적할 수 있도록 합니다.
 */
@Component
class MdcLoggingFilter : OncePerRequestFilter() {
    
    private val pathMatcher = AntPathMatcher()

    companion object {
        const val REQUEST_ID = "request_id"
        const val X_REQUEST_ID_HEADER = "X-RequestID"
        private const val REQUEST_ID_ATTR = "mdc.request_id"
        private const val USER_ID_ATTR = "mdc.user_id"
        private const val START_TIME_ATTR = "mdc.start_time"

        // 필터를 적용하지 않을 URL 패턴 목록
        private val WHITE_LIST = listOf(
            "/actuator/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/favicon.ico"
        )
    }

    // Async dispatch 시에도 MDC를 재설정해야 하므로 false 반환
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    /**
     * HTTP 요청 처리 전후로 MDC 설정 및 로깅을 수행합니다.
     * 
     * 동작 순서:
     * 1. X-RequestID 헤더 확인 (Nginx에서 전달된 request_id)
     * 2. 헤더가 없으면 UUID 기반 고유한 request_id 생성 (8자리)
     * 3. MDC에 request_id 저장
     * 4. 요청 처리 시작 시간 기록
     * 5. 다음 필터 체인으로 요청 전달
     * 6. 요청 처리 완료 후 처리 시간 계산 및 로깅
     * 7. MDC 정리 (finally 블록에서 항상 실행)
     * 
     * @param request HTTP 요청 객체
     * @param response HTTP 응답 객체
     * @param filterChain 필터 체인
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val isAsyncDispatch = request.dispatcherType == DispatcherType.ASYNC

        if (isAsyncDispatch) {
            // Async dispatch: 원본 요청에서 저장해둔 MDC 값을 복원하여 GlobalExceptionHandler 등이 request_id/user_id를 찍을 수 있게 함
            val savedRequestId = request.getAttribute(REQUEST_ID_ATTR) as? String
            val savedUserId = request.getAttribute(USER_ID_ATTR) as? String
            savedRequestId?.let { MDC.put(REQUEST_ID, it) }
            savedUserId?.let { MDC.put(org.depromeet.team3.common.resolver.UserIdArgumentResolver.USER_ID, it) }

            val startTime = request.getAttribute(START_TIME_ATTR) as? Long ?: System.currentTimeMillis()
            try {
                filterChain.doFilter(request, response)
            } finally {
                val duration = System.currentTimeMillis() - startTime
                val logMessage = "HTTP Request Completed - status=${response.status} method=${request.method} uri=${request.requestURI} duration=${duration}ms"
                if (request.method == "GET" && response.status == HttpServletResponse.SC_OK) {
                    logger.debug(logMessage)
                } else {
                    logger.info(logMessage)
                }
                MDC.clear()
            }
            return
        }

        // 최초 요청: request_id 생성 후 request attribute에도 저장 (async dispatch에서 복원용)
        val requestId = request.getHeader(X_REQUEST_ID_HEADER)
            ?: UUID.randomUUID().toString().substring(0, 8)
        MDC.put(REQUEST_ID, requestId)
        request.setAttribute(REQUEST_ID_ATTR, requestId)

        val startTime = System.currentTimeMillis()
        request.setAttribute(START_TIME_ATTR, startTime)

        try {
            filterChain.doFilter(request, response)
        } finally {
            // async가 시작된 경우 HTTP 완료 로그는 async dispatch 시점에 기록
            if (!request.isAsyncStarted) {
                val duration = System.currentTimeMillis() - startTime
                val logMessage = "HTTP Request Completed - status=${response.status} method=${request.method} uri=${request.requestURI} duration=${duration}ms"
                if (request.method == "GET" && response.status == HttpServletResponse.SC_OK) {
                    logger.debug(logMessage)
                } else {
                    logger.info(logMessage)
                }
            } else {
                // async dispatch를 위해 user_id snapshot 저장 (JwtAuthenticationFilter가 MDC에 이미 설정함)
                val userId = MDC.get(org.depromeet.team3.common.resolver.UserIdArgumentResolver.USER_ID)
                userId?.let { request.setAttribute(USER_ID_ATTR, it) }
            }
            MDC.clear()
        }
    }

    /**
     * 화이트리스트에 포함된 URL은 필터를 적용하지 않음
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return WHITE_LIST.any { pattern -> pathMatcher.match(pattern, uri) }
    }
}

package org.depromeet.team3.common.annotation

import io.swagger.v3.oas.annotations.Parameter

/**
 * JWT 토큰에서 사용자 ID를 추출하여 컨트롤러 메서드 파라미터에 주입하는 어노테이션
 *
 * @example
 * ```
 * fun getProfile(@UserId userId: Long): ResponseEntity<UserProfile> {
 *     // userId는 JWT 토큰에서 자동으로 추출됨
 * }
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Parameter(hidden = true)
annotation class UserId

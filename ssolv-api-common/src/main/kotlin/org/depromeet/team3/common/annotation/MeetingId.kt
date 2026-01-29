package org.depromeet.team3.common.annotation

/**
 * 모임 ID 또는 초대 토큰을 받아 Long 타입의 meetingId로 변환해주는 어노테이션
 * PathVariable, RequestParam 등으로 사용할 수 있습니다.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class MeetingId(
    val value: String = ""
)
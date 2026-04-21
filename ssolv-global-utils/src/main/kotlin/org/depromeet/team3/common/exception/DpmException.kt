package org.depromeet.team3.common.exception

/**
 *  애플리케이션 전역 커스텀 예외 최상위 클래스
 *  - 각 도메인 패키지에서 해당 클래스 상속하여 예외 타입 정의해서 사용하시면 됩니다.
 */
abstract class DpmException( // 서비스명 확정되면 이름 수정 예정
    val errorCode: ErrorCode,
    val detail: Map<String, Any?>? = null,
    message: String? = errorCode.message,
) : RuntimeException(message)

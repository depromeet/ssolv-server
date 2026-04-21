package org.depromeet.team3.common.response

import org.depromeet.team3.common.exception.DpmException
import org.depromeet.team3.common.exception.ErrorCode

/**
 *  모든 API 응답을 감싸는 공통 Wrapper 클래스
 */
data class DpmApiResponse<T>( // 서비스명 확정되면 이름 수정할 예정
    val data: T? = null, // 성공 시 내려줄 데이터
    val error: ErrorResponse? = null, // 실패 시 내려줄 에러 정보
) {
    companion object {
        fun <T> ok(data: T): DpmApiResponse<T> = DpmApiResponse(data = data)

        fun ok(): DpmApiResponse<Unit> = DpmApiResponse(data = Unit)

        fun error(errorCode: ErrorCode, detail: Map<String, Any?>? = null): DpmApiResponse<Unit> =
            DpmApiResponse(error = ErrorResponse.from(errorCode, detail))

        fun error(e: DpmException): DpmApiResponse<Unit> = error(e.errorCode, e.detail)
    }
}

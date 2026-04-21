package org.depromeet.team3.common.response

import org.depromeet.team3.common.exception.ErrorCode

data class ErrorResponse(val name: String, val code: String, val message: String? = null, val detail: Map<String, Any?>? = null) {

    companion object {
        fun from(errorCode: ErrorCode, detail: Map<String, Any?>? = null): ErrorResponse = ErrorResponse(
            name = errorCode.name,
            code = errorCode.code,
            message = errorCode.message,
            detail = detail,
        )
    }
}

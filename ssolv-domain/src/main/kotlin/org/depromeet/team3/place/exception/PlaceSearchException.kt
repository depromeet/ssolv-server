package org.depromeet.team3.place.exception

import org.depromeet.team3.common.exception.DpmException
import org.depromeet.team3.common.exception.ErrorCode

/**
 * Place 도메인 관련 예외 클래스
 */
class PlaceSearchException(errorCode: ErrorCode, detail: Map<String, Any?>? = null, message: String? = null) :
    DpmException(
        errorCode = errorCode,
        detail = detail,
        message = message ?: errorCode.message,
    )

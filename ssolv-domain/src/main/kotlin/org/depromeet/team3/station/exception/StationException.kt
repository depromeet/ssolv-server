package org.depromeet.team3.station.exception

import org.depromeet.team3.common.exception.DpmException
import org.depromeet.team3.common.exception.ErrorCode

class StationException(errorCode: ErrorCode, detail: Map<String, Any?>? = null, message: String? = errorCode.message) :
    DpmException(errorCode, detail, message)

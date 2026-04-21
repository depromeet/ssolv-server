package org.depromeet.team3.meetingplace.exception

import org.depromeet.team3.common.exception.DpmException
import org.depromeet.team3.common.exception.ErrorCode

class MeetingPlaceException(errorCode: ErrorCode, detail: Map<String, Any?>? = null, message: String? = errorCode.message) :
    DpmException(errorCode, detail, message)

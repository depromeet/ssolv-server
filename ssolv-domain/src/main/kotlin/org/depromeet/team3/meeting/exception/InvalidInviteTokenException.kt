package org.depromeet.team3.meeting.exception

import org.depromeet.team3.common.exception.DpmException
import org.depromeet.team3.common.exception.ErrorCode

class InvalidInviteTokenException(errorCode: ErrorCode, detail: Map<String, Any?>? = null) : DpmException(errorCode, detail)

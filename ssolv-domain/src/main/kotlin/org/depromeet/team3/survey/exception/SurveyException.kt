package org.depromeet.team3.survey.exception

import org.depromeet.team3.common.exception.DpmException
import org.depromeet.team3.common.exception.ErrorCode

class SurveyException(errorCode: ErrorCode, details: Map<String, Any> = emptyMap()) : DpmException(errorCode, details)

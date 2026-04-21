package org.depromeet.team3.surveyresult.exception

import org.depromeet.team3.common.exception.DpmException
import org.depromeet.team3.common.exception.ErrorCode

class SurveyResultException(errorCode: ErrorCode, details: Map<String, Any> = emptyMap()) : DpmException(errorCode, details)

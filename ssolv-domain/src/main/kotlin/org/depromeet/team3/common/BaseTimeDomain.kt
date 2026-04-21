package org.depromeet.team3.common

import java.time.LocalDateTime

abstract class BaseTimeDomain(open val createdAt: LocalDateTime? = null, open val updatedAt: LocalDateTime? = null)

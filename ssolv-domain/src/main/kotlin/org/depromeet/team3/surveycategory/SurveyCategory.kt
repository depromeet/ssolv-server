package org.depromeet.team3.surveycategory

import org.depromeet.team3.common.BaseTimeDomain
import java.time.LocalDateTime

data class SurveyCategory(
    val id: Long? = null,
    val parentId: Long? = null,
    val level: SurveyCategoryLevel,
    val name: String,
    val sortOrder: Int,
    val isDeleted: Boolean = false,
    override val createdAt: LocalDateTime? = null,
    override val updatedAt: LocalDateTime? = null,
) : BaseTimeDomain(createdAt, updatedAt)
